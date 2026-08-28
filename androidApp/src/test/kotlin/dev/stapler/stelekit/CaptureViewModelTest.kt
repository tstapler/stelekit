// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import arrow.core.Either
import arrow.core.getOrElse
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.domain.CaptureEnrichmentCoordinator
import dev.stapler.stelekit.domain.NoOpTopicEnricher
import dev.stapler.stelekit.domain.ScanResult
import dev.stapler.stelekit.domain.TopicEnricher
import dev.stapler.stelekit.domain.TopicSuggestion
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.util.FileUtils
import dev.stapler.stelekit.util.UuidGenerator
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers Epics 2.1-2.3 of project_plans/stelekit-capture-auto-enrich (ScanState, the debounced
 * collectLatest scan trigger, image-prefix splitting, LLM-enrichment merge, save-time staleness
 * resolution, and SavedCaptureContext capture).
 *
 * `application = Application::class` overrides the manifest-declared `SteleKitApplication`
 * (`AndroidManifest.xml` sets `android:name`) with a plain Application for the class default —
 * see [`updateText does not normalize manually typed whitespace`]. Tests that exercise scan/save
 * need a real [SteleKitApplication] with a real [GraphManager] wired in, so they override
 * `@Config(application = SteleKitApplication::class)` per-method and reflectively inject a
 * lightweight `IN_MEMORY`-backed [GraphManager] (mirrors `GraphManagerEnrichmentCoordinatorTest`'s
 * setup) — `graphManager`/`fileSystem` on [SteleKitApplication] both have a private setter, so
 * reflection is the same accepted pattern `GraphManagerEnrichmentCoordinatorTest` uses for
 * `coordinatorFor`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CaptureViewModelTest {

    // ---- Shared real-GraphManager test harness -----------------------------------------

    private class StubSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    /** Minimal FileSystem stub for GraphManager's own bookkeeping (registry, git detection). */
    private open class StubFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/tmp"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = true
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
        override fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {}
        override fun stopExternalChangeDetection() {}
    }

    /**
     * Builds a real [SteleKitApplication] (Robolectric-instantiated; its own `onCreate()` may
     * fail on unrelated Android subsystems under Robolectric — harmless, swallowed by its own
     * try/catch), then reflectively overrides `graphManager` (real, `IN_MEMORY` backend) and
     * `fileSystem` (real, context-initialized — needed because `GraphWriter`'s markdown write
     * goes through the concrete `PlatformFileSystem`, not a fake).
     */
    private fun newWiredApplication(): Pair<SteleKitApplication, GraphManager> {
        val context = ApplicationProvider.getApplicationContext<SteleKitApplication>()
        DriverFactory.setContext(context)
        // GraphManager.getOrCreateEnrichmentCoordinator() resolves an LlmProviderRegistry that
        // reads through CredentialStore — normally wired by SteleKitApplication.onCreate(),
        // which may not have completed successfully under Robolectric (several unrelated
        // Android subsystems in that method can throw there, swallowed by its own try/catch).
        dev.stapler.stelekit.platform.security.CredentialStore.init(context)

        val graphManager = GraphManager(
            platformSettings = StubSettings(),
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        )
        setPrivateField(context, "graphManager", graphManager)

        val fileSystem = PlatformFileSystem().apply { init(context) }
        setPrivateField(context, "fileSystem", fileSystem)

        return context to graphManager
    }

    private fun setPrivateField(target: Any, name: String, value: Any?) {
        SteleKitApplication::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.set(target, value)
    }

    /** A writable directory under Robolectric's shadowed external-storage Documents dir. */
    private fun newGraphDir(): String {
        val homeDir = android.os.Environment
            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
        val dir = File(homeDir, "capture-vm-test-${UuidGenerator.generateV7()}")
        dir.mkdirs()
        return dir.absolutePath
    }

    private fun openTestGraph(graphManager: GraphManager): String {
        val path = newGraphDir()
        runBlocking { graphManager.openGraph(path) }
        return path
    }

    /**
     * Manually constructs a real [CaptureEnrichmentCoordinator] (real [PageRepository], real
     * background scope, real [dev.stapler.stelekit.domain.PageNameIndex]/matcher build) and
     * seeds it directly into [GraphManager]'s private `coordinatorFor` memoization cache via
     * reflection — the same accepted pattern `GraphManagerEnrichmentCoordinatorTest` uses.
     *
     * This bypasses `GraphManager.getOrCreateEnrichmentCoordinator()`'s normal construction path
     * (`CaptureEnrichmentCoordinator.resolveTopicEnricher` -> `GraphManager.llmProviderRegistry`
     * -> `LlmCredentialStore` -> Android `CredentialStore`/`EncryptedSharedPreferences`), which
     * requires the real Android Keystore (`AndroidKeyStore` provider) and throws
     * `NoSuchAlgorithmException` under Robolectric's plain-JVM unit-test environment — there is
     * no Keystore provider registered there. `topicEnricher` is a plain [NoOpTopicEnricher], so
     * this path is never touched.
     */
    private fun seedRealCoordinator(
        graphManager: GraphManager,
        topicEnricher: TopicEnricher = NoOpTopicEnricher(),
    ): CaptureEnrichmentCoordinator {
        val graphId = graphManager.getActiveGraphId()!!
        val repoSet = graphManager.getActiveRepositorySet()!!
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = CaptureEnrichmentCoordinator(repoSet.pageRepository, coordinatorScope, topicEnricher)
        GraphManager::class.java.getDeclaredField("coordinatorFor").apply {
            isAccessible = true
        }.set(graphManager, graphId to CompletableDeferred(coordinator))
        return coordinator
    }

    /** Reflectively overwrites `coordinatorFor` with an arbitrary (graphId, Deferred) pair —
     * used to seed a deliberately incomplete/failed Deferred for race/failure-injection tests
     * (Story 5.2.1, 5.2.7, 5.2.8), mirroring GraphManagerEnrichmentCoordinatorTest's pattern. */
    private fun setCoordinatorFor(graphManager: GraphManager, graphId: GraphId, deferred: Deferred<CaptureEnrichmentCoordinator>) {
        GraphManager::class.java.getDeclaredField("coordinatorFor").apply {
            isAccessible = true
        }.set(graphManager, graphId to deferred)
    }

    /** Saves a multi-word page (guaranteed auto-apply, not confirm-first) and waits — real wall
     * time, since [dev.stapler.stelekit.domain.PageNameIndex] rebuilds on its own background
     * scope, not Robolectric's shadowed main looper — until the coordinator's matcher is built. */
    private fun seedPageAndAwaitMatcher(
        graphManager: GraphManager,
        pageName: String,
        topicEnricher: TopicEnricher = NoOpTopicEnricher(),
    ): CaptureEnrichmentCoordinator {
        val repoSet = graphManager.getActiveRepositorySet()!!
        val now = Clock.System.now()
        runBlocking {
            val page = Page(
                uuid = PageUuid(UuidGenerator.generateV7()),
                name = pageName,
                createdAt = now,
                updatedAt = now,
            )
            val writeActor = repoSet.writeActor
            if (writeActor != null) {
                writeActor.savePage(page)
            } else {
                @OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)
                repoSet.pageRepository.savePage(page)
            }
        }
        val coordinator = seedRealCoordinator(graphManager, topicEnricher)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (coordinator.pageNameIndex.matcher.value != null) return coordinator
            Thread.sleep(25)
        }
        error("Matcher never became ready for '$pageName'")
    }

    /** Advances the Robolectric main-looper's virtual clock past the 300ms scan debounce, then
     * polls (real sleeps + `idle()`) until [condition] holds or [timeoutMs] elapses — the
     * post-debounce scan work resumes on a real background thread pool, whose completion is not
     * tied to the shadow clock, so a single `idleFor` cannot reliably observe it. */
    private fun awaitScanState(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMillis(350))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            looper.idle()
            Thread.sleep(10)
        }
    }

    private fun awaitSaveState(viewModel: CaptureViewModel, timeoutMs: Long = 5_000) {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + timeoutMs
        while (viewModel.saveState.value == CaptureViewModel.SaveState.Saving &&
            System.currentTimeMillis() < deadline
        ) {
            looper.idle()
            Thread.sleep(10)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readSavedContext(viewModel: CaptureViewModel): Any? {
        val field = CaptureViewModel::class.java.getDeclaredField("savedContext")
        field.isAccessible = true
        return field.get(viewModel)
    }

    private fun setSavedContext(viewModel: CaptureViewModel, ctx: Any?) {
        val field = CaptureViewModel::class.java.getDeclaredField("savedContext")
        field.isAccessible = true
        field.set(viewModel, ctx)
    }

    /**
     * Constructs a `CaptureViewModel.SavedCaptureContext` (private nested data class) via
     * reflection, for tests that need to seed a post-save state (Epic 4.2) without driving
     * a full [CaptureViewModel.save] first.
     */
    private fun newSavedCaptureContext(
        block: Block,
        page: Page,
        blocks: List<Block>,
        graphPath: String,
        graphId: GraphId,
        writer: GraphWriter,
        writeActor: DatabaseWriteActor?,
        pageRepository: PageRepository,
        blockRepository: BlockRepository,
    ): Any {
        val cls = Class.forName("dev.stapler.stelekit.CaptureViewModel\$SavedCaptureContext")
        // Two constructors exist at the bytecode level: the real 9-arg one, and a public
        // synthetic 10-arg one (extra trailing DefaultConstructorMarker) Kotlin generates
        // alongside it — declaredConstructors' order is unspecified, so select explicitly.
        // graphId's ABI parameter type is the unboxed `String` (GraphId is a value class),
        // not `GraphId` itself — pass graphId.value, not the wrapper.
        val ctor = cls.declaredConstructors.first { it.parameterCount == 9 }
        ctor.isAccessible = true
        return ctor.newInstance(
            block, page, blocks, graphPath, graphId.value, writer, writeActor, pageRepository, blockRepository,
        )
    }

    /** Reflectively overwrites the private `_scanState` backing [MutableStateFlow]'s value. */
    @Suppress("UNCHECKED_CAST")
    private fun setScanState(viewModel: CaptureViewModel, state: CaptureViewModel.ScanState) {
        val field = CaptureViewModel::class.java.getDeclaredField("_scanState")
        field.isAccessible = true
        (field.get(viewModel) as MutableStateFlow<CaptureViewModel.ScanState>).value = state
    }

    /** Generic poll loop for async work that resumes on a real background dispatcher. */
    private fun awaitCondition(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            looper.idle()
            Thread.sleep(10)
        }
    }

    /**
     * Starts a background collector on [CaptureViewModel.chipFailure] and blocks (via
     * [onSubscription]) until it has actually attached, so a subsequent `tryEmit` from the
     * view model is guaranteed to be observed rather than racing collector startup.
     */
    private fun collectChipFailures(viewModel: CaptureViewModel): Pair<Job, MutableList<String>> {
        val emitted = CopyOnWriteArrayList<String>()
        val started = CompletableDeferred<Unit>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            viewModel.chipFailure.onSubscription { started.complete(Unit) }.collect { emitted.add(it) }
        }
        runBlocking { started.await() }
        return job to emitted
    }

    /** Counts calls to [getPageByName] while delegating everything else to a real repository. */
    private class CountingPageRepository(private val delegate: PageRepository) : PageRepository by delegate {
        var getPageByNameCallCount = 0
            private set

        override fun getPageByName(name: String): Flow<Either<DomainError, Page?>> {
            getPageByNameCallCount++
            return delegate.getPageByName(name)
        }
    }

    /** Counts calls to [saveBlock] while delegating everything else to a real repository. */
    private class CountingBlockRepository(private val delegate: BlockRepository) : BlockRepository by delegate {
        var saveBlockCallCount = 0
            private set

        @OptIn(DirectRepositoryWrite::class)
        override suspend fun saveBlock(block: Block): Either<DomainError, Unit> {
            saveBlockCallCount++
            return delegate.saveBlock(block)
        }
    }

    /** Counts [writeFile] calls; used to prove a [GraphWriter] built on it was never touched. */
    private open class CountingFileSystem : StubFileSystem() {
        var writeFileCallCount = 0
            private set

        override fun writeFile(path: String, content: String): Boolean {
            writeFileCallCount++
            return true
        }
    }

    /**
     * A [PageRepository] delegate whose [getPageByName] suspends on [gate] before delegating —
     * used to prove `save()` completes without awaiting a slower, unrelated async chip-accept
     * write (Story 5.2.6). `getPageByName` is the only suspend point `createStubPage()` awaits
     * before its own (real) `GraphWriter.savePage` write.
     */
    private class GatedPageRepository(
        private val delegate: PageRepository,
        private val gate: CompletableDeferred<Unit>,
    ) : PageRepository by delegate {
        override fun getPageByName(name: String): Flow<Either<DomainError, Page?>> = flow {
            gate.await()
            emitAll(delegate.getPageByName(name))
        }
    }

    // ---- Existing coverage ---------------------------------------------------------------

    @Test
    fun `updateText does not normalize manually typed whitespace`() {
        val viewModel = CaptureViewModel(ApplicationProvider.getApplicationContext())

        val rawText = "raw   text here"
        viewModel.updateText(rawText)

        assertEquals(rawText, viewModel.captureText.value)
    }

    // ---- Story 2.1.1 / 2.3.1: ScanState staleness + textToSave fallback -------------------

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `save_scanStateNotReady_savesRawCaptureTextImmediately`() {
        val (app, graphManager) = newWiredApplication()
        openTestGraph(graphManager)
        val viewModel = CaptureViewModel(app)

        // No debounce/idle advance at all — scanState is still the initial NotReady, proving
        // save() never awaits/joins the scan before persisting (AC #4).
        viewModel.updateText("Plain unlinked capture text")
        assertEquals(CaptureViewModel.ScanState.NotReady, viewModel.scanState.value)

        viewModel.save()
        awaitSaveState(viewModel)

        assertEquals(CaptureViewModel.SaveState.Saved, viewModel.saveState.value)
        val saved = readSavedContext(viewModel)
        assertNotNull("savedContext must be populated after a successful save", saved)
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `save_scanStateReadyMatchesCaptureText_persistsLinkedTextNotRawText`() {
        val (app, graphManager) = newWiredApplication()
        openTestGraph(graphManager)
        seedPageAndAwaitMatcher(graphManager, "Kotlin Multiplatform")
        val viewModel = CaptureViewModel(app)

        viewModel.updateText("Check out Kotlin Multiplatform today")
        awaitScanState { viewModel.scanState.value is CaptureViewModel.ScanState.Ready }

        val ready = viewModel.scanState.value as CaptureViewModel.ScanState.Ready
        assertTrue(
            "expected the auto-applied wiki-link in linkedText, got: ${ready.result.linkedText}",
            ready.result.linkedText.contains("[[Kotlin Multiplatform]]"),
        )

        viewModel.save()
        awaitSaveState(viewModel)

        assertEquals(CaptureViewModel.SaveState.Saved, viewModel.saveState.value)
        val saved = readSavedContext(viewModel)
        assertNotNull(saved)
        val block = saved!!.javaClass.getDeclaredField("block").apply { isAccessible = true }.get(saved)
        val content = block!!.javaClass.getMethod("getContent").invoke(block) as String
        assertTrue(
            "persisted block content must contain the linked text, not raw: $content",
            content.contains("[[Kotlin Multiplatform]]"),
        )
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `save_readyScanTextMatchesCaptureText_computesTextToSaveWithoutSuspending`() {
        val (app, graphManager) = newWiredApplication()
        openTestGraph(graphManager)
        seedPageAndAwaitMatcher(graphManager, "Kotlin Multiplatform")
        val viewModel = CaptureViewModel(app)

        viewModel.updateText("Notes on Kotlin Multiplatform")
        awaitScanState { viewModel.scanState.value is CaptureViewModel.ScanState.Ready }

        // Happy path: save() reads _scanState.value synchronously — calling it right after
        // observing Ready (no further idling) must still complete without ever blocking on a
        // suspend point in the textToSave computation itself.
        viewModel.save()
        awaitSaveState(viewModel)

        assertEquals(CaptureViewModel.SaveState.Saved, viewModel.saveState.value)
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `save_staleReadyStateTextMismatchesCaptureText_fallsBackToRawTrimmedText`() {
        val (app, graphManager) = newWiredApplication()
        openTestGraph(graphManager)
        seedPageAndAwaitMatcher(graphManager, "Kotlin Multiplatform")
        val viewModel = CaptureViewModel(app)

        viewModel.updateText("Notes on Kotlin Multiplatform")
        awaitScanState { viewModel.scanState.value is CaptureViewModel.ScanState.Ready }
        assertTrue(viewModel.scanState.value is CaptureViewModel.ScanState.Ready)

        // Text changes again, but we do NOT let the new debounce/scan complete before saving —
        // the stale Ready(text = "Notes on Kotlin Multiplatform", ...) must be ignored.
        viewModel.updateText("Notes on Kotlin Multiplatform plus more typed just now")
        viewModel.save()
        awaitSaveState(viewModel)

        assertEquals(CaptureViewModel.SaveState.Saved, viewModel.saveState.value)
        val saved = readSavedContext(viewModel)!!
        val block = saved.javaClass.getDeclaredField("block").apply { isAccessible = true }.get(saved)
        val content = block!!.javaClass.getMethod("getContent").invoke(block) as String
        assertEquals(
            "stale Ready state must be ignored — raw current text (unlinked) is saved instead",
            "Notes on Kotlin Multiplatform plus more typed just now",
            content,
        )
    }

    // ---- Story 2.1.2: debounced collectLatest scan trigger --------------------------------

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `collectLatest_rapidTextChanges_onlyLatestValueProducesReadyState`() {
        val (app, graphManager) = newWiredApplication()
        openTestGraph(graphManager)
        seedPageAndAwaitMatcher(graphManager, "Kotlin Multiplatform")
        val viewModel = CaptureViewModel(app)

        // Rapid updates before the 300ms debounce ever fires — collectLatest must coalesce
        // these into a single scan of the LAST value only.
        viewModel.updateText("Kotlin Mult")
        viewModel.updateText("Kotlin Multip")
        viewModel.updateText("Kotlin Multiplatform is great")

        awaitScanState { viewModel.scanState.value is CaptureViewModel.ScanState.Ready }

        val ready = viewModel.scanState.value as CaptureViewModel.ScanState.Ready
        assertEquals("Kotlin Multiplatform is great", ready.text)
    }

    // ---- Story 2.1.3: image-prefix splitting -----------------------------------------------

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `scan_imageOnlyShareNoCaption_neverScansRawImagePathPrefix`() {
        val (app, graphManager) = newWiredApplication()
        openTestGraph(graphManager)
        // A real (but page-less) coordinator, so the blank-freeText guard is what's actually
        // exercised here — without this, coordinator resolution would fail before ever reaching
        // that guard (see seedRealCoordinator's doc) and land on NotReady for the wrong reason.
        seedRealCoordinator(graphManager)
        val viewModel = CaptureViewModel(app)

        // No caption after the image marker — .trim() upstream in CaptureActivity would have
        // stripped the trailing "\n", leaving no trailing newline at all.
        viewModel.updateText("[image: /data/user/0/dev.stapler.stelekit/cache/share_1700000000000.jpg]")

        // Advance past the debounce window; there's no matcher/graph work to await here since
        // the blank-freeText guard short-circuits before coordinator.scan() is ever reached.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))

        // Blank free text short-circuits to NotReady — the raw file-path string is never
        // handed to the matcher.
        assertEquals(CaptureViewModel.ScanState.NotReady, viewModel.scanState.value)
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `save_imagePrefixWithCaption_recombinesPrefixWithLinkedFreeText`() {
        val (app, graphManager) = newWiredApplication()
        openTestGraph(graphManager)
        seedPageAndAwaitMatcher(graphManager, "Kotlin Multiplatform")
        val viewModel = CaptureViewModel(app)

        viewModel.updateText("[image: /data/user/0/dev.stapler.stelekit/cache/share_1.jpg]\nGreat article about Kotlin Multiplatform")
        awaitScanState { viewModel.scanState.value is CaptureViewModel.ScanState.Ready }

        val ready = viewModel.scanState.value as CaptureViewModel.ScanState.Ready
        assertFalse(
            "the matched name in linkedText must come from freeText only",
            ready.result.linkedText.contains("[image:"),
        )
        assertTrue(ready.result.linkedText.contains("[[Kotlin Multiplatform]]"))

        viewModel.save()
        awaitSaveState(viewModel)

        assertEquals(CaptureViewModel.SaveState.Saved, viewModel.saveState.value)
        val saved = readSavedContext(viewModel)!!
        val block = saved.javaClass.getDeclaredField("block").apply { isAccessible = true }.get(saved)
        val content = block!!.javaClass.getMethod("getContent").invoke(block) as String
        assertTrue(
            "saved text must recombine the image prefix with the linked free text: $content",
            content.startsWith("[image: /data/user/0/dev.stapler.stelekit/cache/share_1.jpg]\n") &&
                content.contains("[[Kotlin Multiplatform]]"),
        )
    }

    // ---- Story 2.2.1: mergeBySource (LLM-enrichment merge) — pure-function unit test ------

    @Test
    fun `mergeBySource_appendsNonDuplicateEnrichedSuggestionsWithoutClearingLocal`() {
        val local = listOf(TopicSuggestion("Note-taking", 0.5f, TopicSuggestion.Source.LOCAL))
        val enriched = listOf(
            TopicSuggestion("Zettelkasten", 0.8f, TopicSuggestion.Source.AI_ENHANCED),
            // normalized duplicate of the local term (different case) — must be suppressed
            TopicSuggestion("note-taking", 0.9f, TopicSuggestion.Source.AI_ENHANCED),
        )

        val merged = invokeMergeBySource(local, enriched)

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.term == "Note-taking" })
        assertTrue(merged.any { it.term == "Zettelkasten" })
        assertFalse(
            "normalized duplicate of a local term must not be appended",
            merged.any { it.term == "note-taking" },
        )
    }

    // mergeBySource is intentionally `private` (plan.md Task 2.2.1b); reflection lets this test
    // exercise the merge logic in isolation without standing up a real LLM provider registry
    // (which GraphManager resolves internally and is not injectable from CaptureViewModel).
    @Suppress("UNCHECKED_CAST")
    private fun invokeMergeBySource(
        local: List<TopicSuggestion>,
        enriched: List<TopicSuggestion>,
    ): List<TopicSuggestion> {
        val app: Application = ApplicationProvider.getApplicationContext()
        val viewModel = CaptureViewModel(app)
        val method = CaptureViewModel::class.java.getDeclaredMethod(
            "mergeBySource",
            List::class.java,
            List::class.java,
        )
        method.isAccessible = true
        return method.invoke(viewModel, local, enriched) as List<TopicSuggestion>
    }

    // ---- Epic 4.1: pre-save chip accept/dismiss --------------------------------------------

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `acceptSuggestion_preSave_realGraphWriterPersistsStubPageFile`() {
        val (app, graphManager) = newWiredApplication()
        val graphPath = openTestGraph(graphManager)
        val viewModel = CaptureViewModel(app)

        viewModel.updateText("Reading about Zettelkasten today")
        setScanState(
            viewModel,
            CaptureViewModel.ScanState.Ready(
                text = viewModel.captureText.value,
                result = ScanResult(
                    linkedText = viewModel.captureText.value,
                    matchedPageNames = emptyList(),
                    topicSuggestions = listOf(TopicSuggestion("Zettelkasten", 0.8f, TopicSuggestion.Source.LOCAL)),
                ),
            ),
        )

        viewModel.acceptSuggestion("Zettelkasten")

        // Synchronous fold — observable with no suspension/idling between the tap and this read.
        val ready = viewModel.scanState.value as CaptureViewModel.ScanState.Ready
        assertTrue(ready.result.linkedText.contains("[[Zettelkasten]]"))
        assertTrue(ready.result.topicSuggestions.single { it.term == "Zettelkasten" }.accepted)

        val stubFile = File(graphPath, "pages/${FileUtils.sanitizeFileName("Zettelkasten")}.md")
        awaitCondition { stubFile.exists() }
        assertTrue("stub page file must be written to disk: ${stubFile.absolutePath}", stubFile.exists())
    }

    @Test
    fun `dismissSuggestion_setsDismissedTrue_noWriteInvoked`() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val viewModel = CaptureViewModel(app)
        viewModel.updateText("Reading about Zettelkasten today")
        setScanState(
            viewModel,
            CaptureViewModel.ScanState.Ready(
                text = viewModel.captureText.value,
                result = ScanResult(
                    linkedText = viewModel.captureText.value,
                    matchedPageNames = emptyList(),
                    topicSuggestions = listOf(TopicSuggestion("Zettelkasten", 0.8f, TopicSuggestion.Source.LOCAL)),
                ),
            ),
        )

        // No graphManager is wired on this plain-Application config — if dismissSuggestion()
        // touched any write path it would NPE/throw here; reaching the assertions below with no
        // exception, on top of the state staying exactly as expected, is itself proof no write
        // was ever attempted.
        viewModel.dismissSuggestion("Zettelkasten")

        val ready = viewModel.scanState.value as CaptureViewModel.ScanState.Ready
        val suggestion = ready.result.topicSuggestions.single { it.term == "Zettelkasten" }
        assertTrue(suggestion.dismissed)
        assertFalse(suggestion.accepted)
        assertFalse(
            "dismiss must never fold a link into linkedText",
            ready.result.linkedText.contains("[[Zettelkasten]]"),
        )
    }

    // ---- Story 4.1.4: acceptExistingLink() / dismissExistingLinkSuggestion() --------------

    @Test
    fun `acceptExistingLink_preSave_foldsLinkSynchronouslyWithNoStubPageWrite`() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val viewModel = CaptureViewModel(app)
        viewModel.updateText("Check out Today for details")
        setScanState(
            viewModel,
            CaptureViewModel.ScanState.Ready(
                text = viewModel.captureText.value,
                result = ScanResult(linkedText = viewModel.captureText.value, matchedPageNames = emptyList()),
                confirmFirstNames = listOf("Today"),
            ),
        )

        // Pre-save (savedContext == null): acceptExistingLink() returns immediately after the
        // fold with no scope.launch at all — nothing here can reach a GraphWriter, so this plain
        // Application (no graphManager wired) completing without exception is itself proof no
        // stub-page write is ever attempted for a confirm-first chip.
        viewModel.acceptExistingLink("Today")

        val ready = viewModel.scanState.value as CaptureViewModel.ScanState.Ready
        assertTrue(ready.result.linkedText.contains("[[Today]]"))
        assertFalse("Today" in ready.confirmFirstNames)
    }

    @Test
    fun `dismissExistingLinkSuggestion_removesFromConfirmFirstNames_noFoldNoWrite`() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val viewModel = CaptureViewModel(app)
        viewModel.updateText("Check out Today for details")
        setScanState(
            viewModel,
            CaptureViewModel.ScanState.Ready(
                text = viewModel.captureText.value,
                result = ScanResult(linkedText = viewModel.captureText.value, matchedPageNames = emptyList()),
                confirmFirstNames = listOf("Today"),
            ),
        )

        viewModel.dismissExistingLinkSuggestion("Today")

        val ready = viewModel.scanState.value as CaptureViewModel.ScanState.Ready
        assertFalse("Today" in ready.confirmFirstNames)
        assertFalse(
            "dismissing a confirm-first chip must never insert the link",
            ready.result.linkedText.contains("[[Today]]"),
        )
    }

    // ---- Story 4.1.3: chip-failure isolation + snackbar signal (AC #7) --------------------

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `acceptSuggestion_preSaveStubWriteFails_isolatedFailureEmitsChipFailureOtherChipsUnaffected`() {
        val (app, graphManager) = newWiredApplication()
        val graphPath = openTestGraph(graphManager)
        // Force the real stub-page write to genuinely fail: the app's fileSystem is a real
        // PlatformFileSystem (a final class — cannot be swapped for a fake), so instead make
        // "pages/" a plain FILE rather than a directory. legacyWriteFile's parentDir.mkdirs()
        // silently no-ops against an existing file, and the subsequent File.writeText() throws
        // (caught, returns false) — a genuine on-disk failure, not a simulated one.
        val pagesPath = File(graphPath, "pages")
        pagesPath.parentFile?.mkdirs()
        pagesPath.createNewFile()
        val viewModel = CaptureViewModel(app)

        viewModel.updateText("Reading about Zettelkasten and Multiplatform today")
        setScanState(
            viewModel,
            CaptureViewModel.ScanState.Ready(
                text = viewModel.captureText.value,
                result = ScanResult(
                    linkedText = viewModel.captureText.value,
                    matchedPageNames = emptyList(),
                    topicSuggestions = listOf(
                        TopicSuggestion("Zettelkasten", 0.8f, TopicSuggestion.Source.LOCAL),
                        TopicSuggestion("Multiplatform", 0.6f, TopicSuggestion.Source.LOCAL),
                    ),
                ),
            ),
        )

        val (job, emitted) = collectChipFailures(viewModel)
        viewModel.acceptSuggestion("Zettelkasten")

        // Synchronous fold happens regardless of the as-yet-unresolved async write outcome.
        val readyAfterAccept = viewModel.scanState.value as CaptureViewModel.ScanState.Ready
        assertTrue(readyAfterAccept.result.linkedText.contains("[[Zettelkasten]]"))

        awaitCondition { emitted.isNotEmpty() }
        job.cancel()

        assertEquals(listOf("Couldn't create page for \"Zettelkasten\""), emitted)

        val finalState = viewModel.scanState.value as CaptureViewModel.ScanState.Ready
        val zettel = finalState.result.topicSuggestions.single { it.term == "Zettelkasten" }
        val multi = finalState.result.topicSuggestions.single { it.term == "Multiplatform" }
        assertTrue("a failed async write must not revert the already-folded link", zettel.accepted)
        assertTrue(finalState.result.linkedText.contains("[[Zettelkasten]]"))
        assertFalse("other pending suggestion must be unaffected by the failure", multi.accepted)
        assertFalse(multi.dismissed)
        assertFalse(
            "other pending suggestion's term must not be folded in",
            finalState.result.linkedText.contains("[[Multiplatform]]"),
        )
    }

    // ---- Epic 4.2: post-save write-back -----------------------------------------------------

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `acceptSuggestion_postSaveWithRealSavedContext_reusesCapturedWriterAndPersistsSecondWrite`() {
        val (app, graphManager) = newWiredApplication()
        val graphPath = openTestGraph(graphManager)
        val viewModel = CaptureViewModel(app)

        viewModel.updateText("Reading about Zettelkasten today")
        viewModel.save()
        awaitSaveState(viewModel)
        assertEquals(CaptureViewModel.SaveState.Saved, viewModel.saveState.value)

        val ctxBefore = readSavedContext(viewModel)!!
        val blockBefore = ctxBefore.javaClass.getDeclaredField("block").apply { isAccessible = true }
            .get(ctxBefore) as Block
        val blockUuidBefore = blockBefore.uuid

        setScanState(
            viewModel,
            CaptureViewModel.ScanState.Ready(
                text = viewModel.captureText.value,
                result = ScanResult(
                    linkedText = viewModel.captureText.value,
                    matchedPageNames = emptyList(),
                    topicSuggestions = listOf(TopicSuggestion("Zettelkasten", 0.8f, TopicSuggestion.Source.LOCAL)),
                ),
            ),
        )

        viewModel.acceptSuggestion("Zettelkasten")

        val stubFile = File(graphPath, "pages/${FileUtils.sanitizeFileName("Zettelkasten")}.md")
        awaitCondition { stubFile.exists() }
        assertTrue("stub page file must be written to disk: ${stubFile.absolutePath}", stubFile.exists())

        awaitCondition {
            val ctx = readSavedContext(viewModel)!!
            val block = ctx.javaClass.getDeclaredField("block").apply { isAccessible = true }.get(ctx)
            val content = block!!.javaClass.getMethod("getContent").invoke(block) as String
            content.contains("[[Zettelkasten]]")
        }

        val ctxAfter = readSavedContext(viewModel)!!
        val blockAfter = ctxAfter.javaClass.getDeclaredField("block").apply { isAccessible = true }
            .get(ctxAfter) as Block
        assertEquals("the second write must update the SAME block, not a new one", blockUuidBefore, blockAfter.uuid)
        assertTrue(blockAfter.content.contains("[[Zettelkasten]]"))
    }

    // ---- AC #7 integration: a failed post-save chip write never touches the original save ----

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `acceptSuggestion_stubPageWriteFails_blockWriteAndMarkdownFlushUnaffected`() {
        val (app, graphManager) = newWiredApplication()
        val graphPath = openTestGraph(graphManager)
        val viewModel = CaptureViewModel(app)

        // Drive a REAL save() to completion first — unlike the pre-save failure-isolation test
        // above (savedContext == null, acceptSuggestion() never reaches performSave() at all),
        // this proves the original capture's block write and markdown flush (Bug-1/Bug-8
        // mitigations in performSave()) survive a subsequent chip-accept failure untouched.
        val originalText = "Reading about Zettelkasten today"
        viewModel.updateText(originalText)
        viewModel.save()
        awaitSaveState(viewModel)
        assertEquals(CaptureViewModel.SaveState.Saved, viewModel.saveState.value)

        val ctxBefore = readSavedContext(viewModel)!!
        val blockBefore = ctxBefore.javaClass.getDeclaredField("block").apply { isAccessible = true }
            .get(ctxBefore) as Block
        val pageBefore = ctxBefore.javaClass.getDeclaredField("page").apply { isAccessible = true }
            .get(ctxBefore) as Page

        val journalFile = File(graphPath, "journals/${FileUtils.sanitizeFileName(pageBefore.name)}.md")
        assertTrue("journal markdown must exist after save(): ${journalFile.absolutePath}", journalFile.exists())
        val journalContentAfterSave = journalFile.readText()
        assertTrue(journalContentAfterSave.contains(originalText))

        val repoSet = graphManager.getActiveRepositorySet()!!
        fun blocksOnDisk() = runBlocking {
            repoSet.blockRepository.getBlocksForPage(pageBefore.uuid).first()
                .getOrElse { error("Failed to read blocks: $it") }
        }
        // ensureTodayJournal() seeds a blank placeholder block for a brand-new journal page, so
        // there are two blocks after save() (the placeholder + our new one) — look up ours by
        // uuid rather than assuming it's the page's only block.
        val blocksAfterSave = blocksOnDisk()
        assertEquals(originalText, blocksAfterSave.single { it.uuid == blockBefore.uuid }.content)

        // Force the accepted chip's stub-page write to fail: corrupt "pages/" into a plain file
        // (same technique as `acceptSuggestion_preSaveStubWriteFails_...` above) so
        // acceptSuggestionPostSave()'s `ctx.writer.savePage(stubPage, ...)` call for the new term
        // page genuinely fails on disk. Journal writes live under "journals/", not "pages/", so
        // this cannot retroactively corrupt what save() already flushed.
        val pagesPath = File(graphPath, "pages")
        pagesPath.parentFile?.mkdirs()
        pagesPath.createNewFile()

        setScanState(
            viewModel,
            CaptureViewModel.ScanState.Ready(
                text = viewModel.captureText.value,
                result = ScanResult(
                    linkedText = viewModel.captureText.value,
                    matchedPageNames = emptyList(),
                    topicSuggestions = listOf(TopicSuggestion("Zettelkasten", 0.8f, TopicSuggestion.Source.LOCAL)),
                ),
            ),
        )

        val (job, emitted) = collectChipFailures(viewModel)
        viewModel.acceptSuggestion("Zettelkasten")

        awaitCondition { emitted.isNotEmpty() }
        job.cancel()

        // (a) the chip failure is isolated/surfaced — same guarantee the pre-save test covers.
        assertEquals(listOf("Couldn't create page for \"Zettelkasten\""), emitted)

        // (b) the original block, as persisted by performSave(), is untouched: the stub-page
        // write failure returns from acceptSuggestionPostSave() before writeLinkedBlockPostSave()
        // is ever reached, so the original save's block write is never re-invoked.
        val blocksAfterFailure = blocksOnDisk()
        assertEquals(
            "the failed chip-accept write must not add or remove any block on the original page",
            blocksAfterSave.size, blocksAfterFailure.size,
        )
        val blockAfterFailure = blocksAfterFailure.single { it.uuid == blockBefore.uuid }
        assertEquals(
            "original block content must remain exactly what performSave() wrote — " +
                "no [[Zettelkasten]] inserted by the failed chip-accept path",
            originalText, blockAfterFailure.content,
        )

        // (c) the original save's markdown flush is untouched on disk.
        assertEquals(
            "journal markdown on disk must be unchanged by the failed chip-accept path",
            journalContentAfterSave, journalFile.readText(),
        )

        // (d) the in-memory savedContext snapshot captured by performSave() was not mutated
        // either — it is only updated on a successful writeLinkedBlockPostSave(), never reached.
        val ctxAfter = readSavedContext(viewModel)!!
        val blockAfter = ctxAfter.javaClass.getDeclaredField("block").apply { isAccessible = true }
            .get(ctxAfter) as Block
        assertEquals(
            "savedContext's captured block must be untouched by the failed chip-accept path",
            blockBefore.content, blockAfter.content,
        )
        assertFalse(blockAfter.content.contains("[[Zettelkasten]]"))
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `acceptSuggestion_postSaveGraphIdMismatch_neverTouchesRepositoryOrWriter`() {
        val (app, graphManager) = newWiredApplication()
        openTestGraph(graphManager)
        val repoSet = graphManager.getActiveRepositorySet()!!
        val viewModel = CaptureViewModel(app)

        val countingPages = CountingPageRepository(repoSet.pageRepository)
        val countingBlocks = CountingBlockRepository(repoSet.blockRepository)
        val countingFileSystem = CountingFileSystem()
        val fakeWriter = GraphWriter(countingFileSystem, writeActor = null)

        val now = Clock.System.now()
        val page = Page(
            uuid = PageUuid(UuidGenerator.generateV7()), name = "2026-08-27",
            createdAt = now, updatedAt = now,
        )
        val block = Block(
            uuid = BlockUuid(UuidGenerator.generateV7()),
            pageUuid = page.uuid,
            content = "Reading about Zettelkasten today",
            position = "a0",
            createdAt = now,
            updatedAt = now,
        )
        val ctx = newSavedCaptureContext(
            block = block,
            page = page,
            blocks = listOf(block),
            graphPath = graphManager.getActiveGraphInfo()!!.path,
            // Deliberately does not match the real active graph id — simulates the user
            // switching graphs in the window between save() and a post-save chip tap.
            graphId = GraphId("mismatched-graph-id"),
            writer = fakeWriter,
            writeActor = null,
            pageRepository = countingPages,
            blockRepository = countingBlocks,
        )
        setSavedContext(viewModel, ctx)

        viewModel.updateText("Reading about Zettelkasten today")
        setScanState(
            viewModel,
            CaptureViewModel.ScanState.Ready(
                text = viewModel.captureText.value,
                result = ScanResult(
                    linkedText = viewModel.captureText.value,
                    matchedPageNames = emptyList(),
                    topicSuggestions = listOf(TopicSuggestion("Zettelkasten", 0.8f, TopicSuggestion.Source.LOCAL)),
                ),
            ),
        )

        val (job, emitted) = collectChipFailures(viewModel)
        viewModel.acceptSuggestion("Zettelkasten")

        awaitCondition { emitted.isNotEmpty() }
        job.cancel()

        assertEquals(listOf("Couldn't link \"Zettelkasten\" — the graph changed"), emitted)
        assertEquals(0, countingPages.getPageByNameCallCount)
        assertEquals(0, countingBlocks.saveBlockCallCount)
        assertEquals(0, countingFileSystem.writeFileCallCount)
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `acceptExistingLink_postSaveActorChannelClosed_closedSendChannelExceptionCaughtWithDistinctMessage`() {
        val (app, graphManager) = newWiredApplication()
        openTestGraph(graphManager)
        val repoSet = graphManager.getActiveRepositorySet()!!
        val viewModel = CaptureViewModel(app)

        val now = Clock.System.now()
        val page = Page(
            uuid = PageUuid(UuidGenerator.generateV7()), name = "2026-08-27",
            createdAt = now, updatedAt = now,
        )
        val block = Block(
            uuid = BlockUuid(UuidGenerator.generateV7()),
            pageUuid = page.uuid,
            content = "Check out Today for details",
            position = "a0",
            createdAt = now,
            updatedAt = now,
        )
        val writer = GraphWriter(app.fileSystem, writeActor = repoSet.writeActor)
        val ctx = newSavedCaptureContext(
            block = block,
            page = page,
            blocks = listOf(block),
            graphPath = graphManager.getActiveGraphInfo()!!.path,
            graphId = graphManager.getActiveGraphId()!!,
            writer = writer,
            writeActor = repoSet.writeActor,
            pageRepository = repoSet.pageRepository,
            blockRepository = repoSet.blockRepository,
        )
        setSavedContext(viewModel, ctx)

        viewModel.updateText("Check out Today for details")
        setScanState(
            viewModel,
            CaptureViewModel.ScanState.Ready(
                text = viewModel.captureText.value,
                result = ScanResult(linkedText = viewModel.captureText.value, matchedPageNames = emptyList()),
                confirmFirstNames = listOf("Today"),
            ),
        )

        // Simulate a graph-switch race closing the actor's channel between the identity guard
        // passing and the actual saveBlock() send.
        repoSet.writeActor!!.close()

        val (job, emitted) = collectChipFailures(viewModel)
        viewModel.acceptExistingLink("Today")

        awaitCondition { emitted.isNotEmpty() }
        job.cancel()

        assertEquals(listOf("Couldn't link \"Today\" — the graph changed"), emitted)
        assertNotEquals(
            "must be distinct from performSave()'s own ClosedSendChannelException message",
            "Graph switched during save — please retry",
            emitted.single(),
        )
    }

    // ---- Story 5.2.6: chip-accept/save race — save() must not await the slower write ------

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `acceptSuggestionThenSave_synchronousFoldWinsRace_saveCompletesWithoutAwaitingSlowerStubWrite`() {
        val (app, graphManager) = newWiredApplication()
        openTestGraph(graphManager)
        val viewModel = CaptureViewModel(app)

        val gate = CompletableDeferred<Unit>()
        // The app's fileSystem is a real PlatformFileSystem (a final class — cannot be swapped
        // for a fake), so gate one layer up instead: createStubPage()'s existence check
        // (repoSet.pageRepository.getPageByName) is the only suspend point before the stub
        // write, and PageRepository IS an interface — wrap it to block there. save()'s own
        // journal write goes through the SAME RepositorySet's journalService/blockRepository/
        // writeActor (untouched by this copy), so it is never gated.
        val repoSet = graphManager.getActiveRepositorySet()!!
        val gatedRepoSet = repoSet.copy(pageRepository = GatedPageRepository(repoSet.pageRepository, gate))
        val activeRepositorySetField = GraphManager::class.java.getDeclaredField("_activeRepositorySet")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (activeRepositorySetField.get(graphManager) as MutableStateFlow<Any?>).value = gatedRepoSet

        viewModel.updateText("Reading about Zettelkasten today")
        setScanState(
            viewModel,
            CaptureViewModel.ScanState.Ready(
                text = viewModel.captureText.value,
                result = ScanResult(
                    linkedText = viewModel.captureText.value,
                    matchedPageNames = emptyList(),
                    topicSuggestions = listOf(TopicSuggestion("Zettelkasten", 0.8f, TopicSuggestion.Source.LOCAL)),
                ),
            ),
        )

        viewModel.acceptSuggestion("Zettelkasten")
        // Synchronous fold already landed before scope.launch even runs — save() issued right
        // after already observes it, with no lock/mutex needed between the two calls.
        val readyAfterAccept = viewModel.scanState.value as CaptureViewModel.ScanState.Ready
        assertTrue(readyAfterAccept.result.linkedText.contains("[[Zettelkasten]]"))

        viewModel.save()
        awaitSaveState(viewModel)

        assertEquals(CaptureViewModel.SaveState.Saved, viewModel.saveState.value)
        assertFalse(
            "save() must complete without waiting on the deliberately-slow stub-page write",
            gate.isCompleted,
        )

        val saved = readSavedContext(viewModel)!!
        val block = saved.javaClass.getDeclaredField("block").apply { isAccessible = true }.get(saved)
        val content = block!!.javaClass.getMethod("getContent").invoke(block) as String
        assertTrue(
            "the persisted block must contain the accepted link despite the race",
            content.contains("[[Zettelkasten]]"),
        )

        gate.complete(Unit) // release the still-pending stub write so it doesn't leak past the test
    }

    // ---- Story 5.2.1: onNewIntent stale-scan test (PF-6) -----------------------------------

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `collectLatest_secondShareIntentSupersedesInFlightScan_onlyLatestResultReachesScanState`() {
        val (app, graphManager) = newWiredApplication()
        openTestGraph(graphManager)
        val graphId = graphManager.getActiveGraphId()!!

        // Pre-build a real, matcher-ready coordinator (real PageRepository/PageNameIndex) OFF to
        // the side, before ever wiring it into GraphManager's memoization cache — this is the
        // coordinator intent B will eventually resolve to.
        val readyCoordinator = seedPageAndAwaitMatcher(graphManager, "Kotlin Multiplatform")

        // Now replace the cache with a deliberately incomplete Deferred: getOrCreateEnrichmentCoordinator()'s
        // deferred.await() (GraphManager.kt) is a genuine, real production suspension point — no
        // fake/subclass of the (non-open) CaptureEnrichmentCoordinator is needed to construct the
        // race deterministically.
        val stuckDeferred = CompletableDeferred<CaptureEnrichmentCoordinator>()
        setCoordinatorFor(graphManager, graphId, stuckDeferred)

        val viewModel = CaptureViewModel(app)

        // Intent A's text arrives first; once the debounce fires, collectLatest's block suspends
        // awaiting the coordinator Deferred, never reaching coordinator.scan() or _scanState.
        viewModel.initializeText("Intent A text, should never be observed")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        Thread.sleep(150) // let the suspended await() actually register
        assertEquals(
            "A's in-flight scan must not have applied anything yet",
            CaptureViewModel.ScanState.NotReady,
            viewModel.scanState.value,
        )

        // Intent B supersedes A before A's coordinator resolution ever completes — mirrors
        // CaptureActivity.onNewIntent's real sequence: field cleared, then re-initialized.
        viewModel.updateText("")
        viewModel.initializeText("Reading about Kotlin Multiplatform")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        Thread.sleep(150) // let collectLatest cancel A's suspended await() and start B's

        // Only now resolve the coordinator — if A's coroutine were still alive it would resume
        // here too, but collectLatest's cancellation (triggered by B superseding A above) must
        // have already torn it down.
        stuckDeferred.complete(readyCoordinator)

        awaitScanState { viewModel.scanState.value is CaptureViewModel.ScanState.Ready }

        val ready = viewModel.scanState.value as CaptureViewModel.ScanState.Ready
        assertEquals(
            "only intent B's text may ever reach _scanState",
            "Reading about Kotlin Multiplatform",
            ready.text,
        )
        assertTrue(ready.result.linkedText.contains("[[Kotlin Multiplatform]]"))
    }

    // ---- Story 5.2.3: AC #9 post-save write path — reused writer/writeActor, fakes/spies ---

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `acceptSuggestion_postSaveWithFakeSavedContext_reusesCapturedWriterAndWriteActor`() {
        val (app, graphManager) = newWiredApplication()
        openTestGraph(graphManager)
        val repoSet = graphManager.getActiveRepositorySet()!!
        val viewModel = CaptureViewModel(app)

        val countingPages = CountingPageRepository(repoSet.pageRepository)
        val countingBlocks = CountingBlockRepository(repoSet.blockRepository)
        val countingFileSystem = CountingFileSystem()
        // Real writeActor (DB write) + counting-file-system-backed GraphWriter (markdown flush) —
        // both captured exactly once, at construction, and never re-resolved from graphManager.
        val fakeWriter = GraphWriter(countingFileSystem, writeActor = repoSet.writeActor)

        val now = Clock.System.now()
        val page = repoSet.journalService.let { runBlocking { it.ensureTodayJournal() } }
        val block = Block(
            uuid = BlockUuid(UuidGenerator.generateV7()),
            pageUuid = page.uuid,
            content = "Reading about Zettelkasten today",
            position = "a0",
            createdAt = now,
            updatedAt = now,
        )
        runBlocking { repoSet.writeActor!!.saveBlock(block) }

        val ctx = newSavedCaptureContext(
            block = block,
            page = page,
            blocks = listOf(block),
            graphPath = graphManager.getActiveGraphInfo()!!.path,
            graphId = graphManager.getActiveGraphId()!!,
            writer = fakeWriter,
            writeActor = repoSet.writeActor,
            pageRepository = countingPages,
            blockRepository = countingBlocks,
        )
        setSavedContext(viewModel, ctx)

        viewModel.updateText(block.content)
        setScanState(
            viewModel,
            CaptureViewModel.ScanState.Ready(
                text = block.content,
                result = ScanResult(
                    linkedText = block.content,
                    matchedPageNames = emptyList(),
                    topicSuggestions = listOf(TopicSuggestion("Zettelkasten", 0.8f, TopicSuggestion.Source.LOCAL)),
                ),
            ),
        )

        viewModel.acceptSuggestion("Zettelkasten")

        // Poll until the fold is fully persisted (not just "a file write happened") — writeFile()
        // incrementing is not itself proof that writeLinkedBlockPostSave()'s subsequent
        // savedContext reassignment (the very next line) has executed yet.
        awaitCondition {
            val c = readSavedContext(viewModel)!!
            val b = c.javaClass.getDeclaredField("block").apply { isAccessible = true }.get(c)
            (b!!.javaClass.getMethod("getContent").invoke(b) as String).contains("[[Zettelkasten]]")
        }

        // Exactly one additional saveBlock/savePage pair: a stub-page file write for the new page,
        // then the markdown flush of the block's own page — both through the SAME fakeWriter.
        assertEquals("exactly one stub-page-existence check", 1, countingPages.getPageByNameCallCount)
        assertEquals(
            "exactly one additional write pair (stub page create + markdown flush), same writer reused",
            2,
            countingFileSystem.writeFileCallCount,
        )

        val ctxAfter = readSavedContext(viewModel)!!
        val writerAfter = ctxAfter.javaClass.getDeclaredField("writer").apply { isAccessible = true }.get(ctxAfter)
        val writeActorAfter = ctxAfter.javaClass.getDeclaredField("writeActor").apply { isAccessible = true }.get(ctxAfter)
        assertTrue("the exact fakeWriter instance from performSave() must be reused", writerAfter === fakeWriter)
        assertTrue("the exact writeActor instance from performSave() must be reused", writeActorAfter === repoSet.writeActor)

        val blockAfter = ctxAfter.javaClass.getDeclaredField("block").apply { isAccessible = true }.get(ctxAfter)
        val content = blockAfter!!.javaClass.getMethod("getContent").invoke(blockAfter) as String
        assertTrue(content.contains("[[Zettelkasten]]"))
    }

    // ---- Story 5.2.7: scan collector survives a Throwable (Blocker #3) ---------------------

    /**
     * `CaptureEnrichmentCoordinator` is a concrete (non-`open`) class with no injectable failure
     * seam for `scan()` itself, and `PageNameIndex` already swallows a `Throwable` from
     * `PageRepository.getPageNameEntries()` internally (degrades to a `null` matcher — see
     * `PageNameIndex.kt`'s own `.catch { }` guard), so a throwing repository never lets a
     * `Throwable` escape `coordinator.scan()`; it just yields `ScanOutcome.MatcherNotReady`,
     * which is not what this story needs to exercise. The genuinely reachable seam is
     * `GraphManager.getOrCreateEnrichmentCoordinator()`'s `deferred.await()` (real production
     * code, same mechanism `GraphManagerEnrichmentCoordinatorTest`'s eviction test already
     * exercises): a `Deferred` that completed exceptionally throws there, landing in the exact
     * same per-iteration `try/catch` (Task 2.1.2b) this story is about — the throw site differs
     * from a literal `coordinator.scan()` call, but the mechanism under test (the collectLatest
     * body's blanket per-iteration catch keeping the collector alive across ANY Throwable in its
     * try block) is identical either way.
     */
    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `collectLatest_scanThrowsOnFirstAttempt_secondTextChangeStillProducesReadyState`() {
        val (app, graphManager) = newWiredApplication()
        openTestGraph(graphManager)
        val graphId = graphManager.getActiveGraphId()!!
        val goodCoordinator = seedPageAndAwaitMatcher(graphManager, "Kotlin Multiplatform")

        val failedDeferred = CompletableDeferred<CaptureEnrichmentCoordinator>()
        failedDeferred.completeExceptionally(OutOfMemoryError("simulated OOM during scan"))
        setCoordinatorFor(graphManager, graphId, failedDeferred)

        val viewModel = CaptureViewModel(app)

        // First attempt: coordinator resolution throws — must degrade to NotReady without
        // crashing or killing the collector.
        viewModel.updateText("first attempt should fail")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        Thread.sleep(150)
        assertEquals(CaptureViewModel.ScanState.NotReady, viewModel.scanState.value)

        // Re-seed a healthy coordinator for the second attempt.
        setCoordinatorFor(graphManager, graphId, CompletableDeferred(goodCoordinator))

        // Second (distinct) text change: the collector must still be alive and produce a normal
        // Ready result — proving the per-iteration try/catch, not just the CoroutineExceptionHandler,
        // is what kept collectLatest running.
        viewModel.updateText("Reading about Kotlin Multiplatform")
        awaitScanState { viewModel.scanState.value is CaptureViewModel.ScanState.Ready }

        val ready = viewModel.scanState.value as CaptureViewModel.ScanState.Ready
        assertTrue(ready.result.linkedText.contains("[[Kotlin Multiplatform]]"))
    }

    // ---- Story 5.2.8: AC #5 — save() never suspends on coordinator/scan work ---------------

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `save_scanInFlightAgainstStuckCoordinator_completesWithoutAwaitingScan`() {
        val (app, graphManager) = newWiredApplication()
        openTestGraph(graphManager)
        val graphId = graphManager.getActiveGraphId()!!

        // A coordinator resolution the test deliberately never resolves — a scan that never
        // completes, in flight for as long as the test runs.
        val stuckDeferred = CompletableDeferred<CaptureEnrichmentCoordinator>()
        setCoordinatorFor(graphManager, graphId, stuckDeferred)

        val viewModel = CaptureViewModel(app)

        viewModel.updateText("Some capture text while a scan is stuck")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        Thread.sleep(150) // let collectLatest actually reach and suspend on the stuck await()
        assertEquals(CaptureViewModel.ScanState.NotReady, viewModel.scanState.value)

        viewModel.save()
        awaitSaveState(viewModel)

        assertEquals(
            "save() must complete without ever awaiting the stuck coordinator/scan work",
            CaptureViewModel.SaveState.Saved,
            viewModel.saveState.value,
        )
        assertFalse("the coordinator resolution must still be unresolved", stuckDeferred.isCompleted)

        stuckDeferred.completeExceptionally(java.util.concurrent.CancellationException("test cleanup"))
    }

    // ---- Story 5.2.10: confirm-first chip post-save accept — zero stub-page writes ---------

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `acceptExistingLink_postSaveSuccess_reusesWriterWithZeroStubPageWrites`() {
        val (app, graphManager) = newWiredApplication()
        openTestGraph(graphManager)
        val repoSet = graphManager.getActiveRepositorySet()!!
        val viewModel = CaptureViewModel(app)

        val countingPages = CountingPageRepository(repoSet.pageRepository)
        val countingFileSystem = CountingFileSystem()
        val fakeWriter = GraphWriter(countingFileSystem, writeActor = repoSet.writeActor)

        val now = Clock.System.now()
        val page = runBlocking { repoSet.journalService.ensureTodayJournal() }
        val block = Block(
            uuid = BlockUuid(UuidGenerator.generateV7()),
            pageUuid = page.uuid,
            content = "Check out Today for details",
            position = "a0",
            createdAt = now,
            updatedAt = now,
        )
        runBlocking { repoSet.writeActor!!.saveBlock(block) }

        val ctx = newSavedCaptureContext(
            block = block,
            page = page,
            blocks = listOf(block),
            graphPath = graphManager.getActiveGraphInfo()!!.path,
            graphId = graphManager.getActiveGraphId()!!,
            writer = fakeWriter,
            writeActor = repoSet.writeActor,
            pageRepository = countingPages,
            blockRepository = repoSet.blockRepository,
        )
        setSavedContext(viewModel, ctx)

        viewModel.updateText(block.content)
        setScanState(
            viewModel,
            CaptureViewModel.ScanState.Ready(
                text = block.content,
                result = ScanResult(linkedText = block.content, matchedPageNames = emptyList()),
                confirmFirstNames = listOf("Today"),
            ),
        )

        viewModel.acceptExistingLink("Today")

        awaitCondition {
            val c = readSavedContext(viewModel)!!
            val b = c.javaClass.getDeclaredField("block").apply { isAccessible = true }.get(c)
            (b!!.javaClass.getMethod("getContent").invoke(b) as String).contains("[[Today]]")
        }

        // Confirm-first accept never re-checks or creates a stub page — the coordinator already
        // confirmed the page exists before offering it in confirmFirstNames.
        assertEquals("confirm-first accept must never check page existence", 0, countingPages.getPageByNameCallCount)
        assertEquals(
            "confirm-first accept writes only the markdown flush, never a stub-page file",
            1,
            countingFileSystem.writeFileCallCount,
        )
    }

    // ---- AC #3: enhance() fire-and-forget merge path, end-to-end with a fake TopicEnricher --

    private class FakeTopicEnricher(private val result: suspend () -> List<TopicSuggestion>) : TopicEnricher {
        override suspend fun enhance(rawText: String, localSuggestions: List<TopicSuggestion>) = result()
    }

    /**
     * Exercises the fire-and-forget `enhance()` merge path end-to-end without ever touching
     * `LlmProviderRegistry`/`ClaudeTopicEnricher`/`AndroidCredentialStore` (which throws
     * `NoSuchAlgorithmException` under Robolectric — no `AndroidKeyStore` provider). A fake
     * `TopicEnricher` (the plain `fun interface`) is wired directly into a real
     * `CaptureEnrichmentCoordinator`, the same way `seedRealCoordinator`/`seedPageAndAwaitMatcher`
     * already inject `NoOpTopicEnricher` — proving the merge/discard-if-stale/non-destructive
     * behavior with production `CaptureViewModel` code, just a simpler enrichment tier.
     */
    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun `enhance_llmProviderConfigured_mergesEnrichedSuggestionsNonDestructivelyIntoScanState`() {
        val (app, graphManager) = newWiredApplication()
        openTestGraph(graphManager)
        val enricher = FakeTopicEnricher {
            listOf(TopicSuggestion("Zettelkasten", 0.8f, TopicSuggestion.Source.AI_ENHANCED))
        }
        seedPageAndAwaitMatcher(graphManager, "Kotlin Multiplatform", topicEnricher = enricher)
        val viewModel = CaptureViewModel(app)

        viewModel.updateText("Reading about Kotlin Multiplatform")
        awaitScanState { viewModel.scanState.value is CaptureViewModel.ScanState.Ready }

        // Wait for the fire-and-forget enhance() coroutine's merge to land on top of the local
        // scan result already in _scanState.
        awaitCondition {
            val ready = viewModel.scanState.value as? CaptureViewModel.ScanState.Ready
            ready?.result?.topicSuggestions?.any { it.term == "Zettelkasten" } == true
        }

        val ready = viewModel.scanState.value as CaptureViewModel.ScanState.Ready
        val zettel = ready.result.topicSuggestions.single { it.term == "Zettelkasten" }
        assertEquals(TopicSuggestion.Source.AI_ENHANCED, zettel.source)
        // Non-destructive: the local auto-link result from the matcher hit is untouched.
        assertTrue(
            "AI-sourced suggestions must be appended, never replacing the local scan result",
            ready.result.linkedText.contains("[[Kotlin Multiplatform]]"),
        )
    }
}
