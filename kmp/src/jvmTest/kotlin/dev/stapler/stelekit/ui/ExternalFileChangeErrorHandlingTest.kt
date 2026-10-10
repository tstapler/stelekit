package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.ExternalFileChange
import dev.stapler.stelekit.db.GraphEpoch
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphLoaderPort
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.db.WriteError
import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import dev.stapler.stelekit.ui.state.BlockStateManager
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.vault.CryptoLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Regression coverage for `StelekitViewModel.observeExternalFileChanges`'s fire-and-forget
 * `scope.launch { graphLoader.applyExternalFileChange(...) }` -- previously this had no local
 * catch, so a single malformed external file (e.g. the PNG-as-page bug fixed alongside this)
 * threw all the way to `scope`'s top-level `CoroutineExceptionHandler`, setting `fatalError` and
 * showing a full-screen error for the *entire app* over one bad file, killing the standing
 * collector for the rest of the session in the process.
 *
 * Uses a minimal [GraphLoaderPort] fake whose `applyExternalFileChange` always throws, rather
 * than trying to reproduce a specific parse failure through the real [GraphLoader] -- the fix
 * under test is about containment of *any* exception from that call, not about one particular
 * cause.
 */
class ExternalFileChangeErrorHandlingTest {

    private class ThrowingGraphLoaderPort : GraphLoaderPort {
        private val _externalFileChanges = MutableSharedFlow<ExternalFileChange>(extraBufferCapacity = 4)
        override val externalFileChanges: SharedFlow<ExternalFileChange> = _externalFileChanges
        private val _writeErrors = MutableSharedFlow<WriteError>(extraBufferCapacity = 1)
        override val writeErrors: SharedFlow<WriteError> = _writeErrors

        suspend fun emit(change: ExternalFileChange) = _externalFileChanges.emit(change)

        override fun setActivePageUuids(uuids: StateFlow<Set<String>>?) {}
        override fun setUnsavedPageUuids(uuids: StateFlow<Set<String>>?) {}
        override fun setCryptoLayer(layer: CryptoLayer?) {}
        override fun closeAndClearCryptoLayer() {}
        override suspend fun loadGraphProgressive(
            graphPath: String,
            immediateJournalCount: Int,
            onProgress: (String) -> Unit,
            onPhase1Complete: () -> Unit,
            onFullyLoaded: () -> Unit,
        ) {}
        override suspend fun indexRemainingPages(onProgress: (String) -> Unit) {}
        override suspend fun loadPageByName(pageName: PageName): Page? = null
        override suspend fun loadFullPage(pageUuid: String, force: Boolean) {}
        override fun cancelBackgroundWork() {}
        override suspend fun parseAndSavePage(
            filePath: FilePath,
            content: String,
            mode: ParseMode,
            priority: DatabaseWriteActor.Priority,
        ) {}

        // The call under test -- always throws, simulating any downstream parse/validation failure.
        override suspend fun applyExternalFileChange(
            filePath: FilePath,
            content: String,
            mode: ParseMode,
            priority: DatabaseWriteActor.Priority,
        ) {
            throw IllegalArgumentException("simulated malformed external file")
        }
    }

    @Test
    fun `a throwing applyExternalFileChange does not set fatalError`() = runBlocking {
        val pageRepo = FakePageRepository(emptyList())
        val blockRepo = FakeBlockRepository(emptyMap())
        val throwingLoader = ThrowingGraphLoaderPort()
        // BlockStateManager takes its own separate (real, harmless) GraphLoader -- unrelated to
        // the ThrowingGraphLoaderPort wired into StelekitViewModelDependencies below, which is
        // the only thing observeExternalFileChanges actually calls.
        val realGraphLoader = GraphLoader(
            FakeFileSystem(),
            pageRepo,
            blockRepo,
            externalWriteActor = DatabaseWriteActor(blockRepo, pageRepo, scope = CoroutineScope(Dispatchers.Unconfined)),
        )
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val searchRepo = InMemorySearchRepository()
        @Suppress("DEPRECATION")
        val graphWriter = GraphWriter(PlatformFileSystem(), pageRepository = pageRepo).also {
            it.currentEpoch = GraphEpoch(GraphId("ext-change-error-test"), graphPath = "", sequence = 1L)
        }
        val bsm = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = realGraphLoader,
            scope = scope,
            graphWriter = graphWriter,
            pageRepository = pageRepo,
            graphPathProvider = { "" },
        )
        val viewModel = StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = PlatformFileSystem(),
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = searchRepo,
                graphLoader = throwingLoader,
                graphWriter = graphWriter,
                platformSettings = InMemorySettings(),
                scope = scope,
                blockStateManager = bsm,
            )
        )

        // observeExternalFileChanges() only starts collecting once startAutoSave() is called --
        // without this, emit() below would just buffer into the flow with no collector running
        // at all, and the test would pass vacuously regardless of whether the fix is present.
        viewModel.startAutoSave()

        // No page is open (state.currentScreen isn't a PageView, activePageUuids is empty), so
        // observeExternalFileChanges takes the "not currently viewed" branch -- the one whose
        // fire-and-forget applyExternalFileChange launch previously had no local catch. Every
        // dispatcher involved (scope, DatabaseWriteActor's scope) is Unconfined/runBlocking, so
        // this emit runs the whole chain -- collect, the launched applyExternalFileChange call,
        // and (pre-fix) the CoroutineExceptionHandler -- synchronously before returning.
        throwingLoader.emit(
            ExternalFileChange(filePath = "/graph/assets/image.png", content = "PNG", suppress = {})
        )

        assertNull(
            viewModel.uiState.value.fatalError,
            "a single malformed external file must not set fatalError for the whole app",
        )
    }
}
