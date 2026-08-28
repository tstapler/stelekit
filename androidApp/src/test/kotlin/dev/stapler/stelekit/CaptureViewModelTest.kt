// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.domain.CaptureEnrichmentCoordinator
import dev.stapler.stelekit.domain.NoOpTopicEnricher
import dev.stapler.stelekit.domain.TopicSuggestion
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.util.UuidGenerator
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private fun seedRealCoordinator(graphManager: GraphManager): CaptureEnrichmentCoordinator {
        val graphId = graphManager.getActiveGraphId()!!
        val repoSet = graphManager.getActiveRepositorySet()!!
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = CaptureEnrichmentCoordinator(repoSet.pageRepository, coordinatorScope, NoOpTopicEnricher())
        GraphManager::class.java.getDeclaredField("coordinatorFor").apply {
            isAccessible = true
        }.set(graphManager, graphId to CompletableDeferred(coordinator))
        return coordinator
    }

    /** Saves a multi-word page (guaranteed auto-apply, not confirm-first) and waits — real wall
     * time, since [dev.stapler.stelekit.domain.PageNameIndex] rebuilds on its own background
     * scope, not Robolectric's shadowed main looper — until the coordinator's matcher is built. */
    private fun seedPageAndAwaitMatcher(graphManager: GraphManager, pageName: String) {
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
        val coordinator = seedRealCoordinator(graphManager)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (coordinator.pageNameIndex.matcher.value != null) return
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
}
