package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.ExternalFileChange
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphLoaderPort
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.db.WriteError
import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import dev.stapler.stelekit.vault.CryptoLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for StelekitViewModel's loading state machine.
 *
 * These tests verify that isLoading always transitions back to false regardless of how
 * the load completes — normal success, missing directory, or scope cancellation.
 * They would have caught the CancellationException-swallowing bug that left the
 * loading spinner stuck forever.
 */
class StelekitViewModelLoadingTest {

    /** A FakeFileSystem that reports all directories as non-existent and uncreatable. */
    private class MissingDirectoryFileSystem : FakeFileSystem() {
        override fun directoryExists(path: String): Boolean = false
        override fun createDirectory(path: String): Boolean = false
    }

    private fun makeViewModel(
        fileSystem: FakeFileSystem = FakeFileSystem(),
        settings: InMemorySettings = InMemorySettings(),
        graphLoader: GraphLoaderPort? = null,
    ): StelekitViewModel {
        val pageRepo = FakePageRepository()
        val blockRepo = FakeBlockRepository()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val resolvedLoader = graphLoader ?: GraphLoader(fileSystem, pageRepo, blockRepo)
        val graphWriter = GraphWriter(PlatformFileSystem())
        val searchRepo = InMemorySearchRepository()
        return StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = fileSystem,
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = searchRepo,
                graphLoader = resolvedLoader,
                graphWriter = graphWriter,
                platformSettings = settings,
                scope = scope,
            )
        )
    }

    // ─── Normal load path ────────────────────────────────────────────────────

    @Test
    fun isLoading_becomes_false_after_graph_loads_successfully() = runBlocking {
        val vm = makeViewModel()

        // setGraphPath triggers loadGraph on the ViewModel's scope
        vm.setGraphPath("/tmp/test-graph")

        // isLoading must reach false within 5 seconds — FakeFileSystem returns empty
        // file lists so Phase 1 completes almost immediately
        withTimeout(5_000) {
            vm.uiState.first { !it.isLoading }
        }

        assertFalse(vm.uiState.value.isLoading, "isLoading must be false after Phase 1 completes")
    }

    @Test
    fun isFullyLoaded_becomes_true_after_graph_loads_completely() = runBlocking {
        val vm = makeViewModel()

        vm.setGraphPath("/tmp/test-graph")

        withTimeout(30_000) {
            vm.uiState.first { it.isFullyLoaded }
        }

        assertTrue(vm.uiState.value.isFullyLoaded, "isFullyLoaded must be true after full load")
        assertFalse(vm.uiState.value.isLoading, "isLoading must be false when isFullyLoaded")
    }

    // ─── Error path ──────────────────────────────────────────────────────────

    @Test
    fun isLoading_becomes_false_when_graph_directory_not_found() = runBlocking {
        val vm = makeViewModel(fileSystem = MissingDirectoryFileSystem())

        vm.setGraphPath("/nonexistent/path")

        // The else-branch (directory not found) sets isLoading=false synchronously-ish
        withTimeout(5_000) {
            vm.uiState.first { !it.isLoading }
        }

        assertFalse(vm.uiState.value.isLoading, "isLoading must be false when directory is missing")
    }

    @Test
    fun onboardingCompleted_is_reset_when_graph_directory_not_found() = runBlocking {
        val settings = InMemorySettings()
        settings.putBoolean("onboardingCompleted", true)
        val vm = makeViewModel(fileSystem = MissingDirectoryFileSystem(), settings = settings)

        vm.setGraphPath("/nonexistent/path")

        withTimeout(5_000) {
            vm.uiState.first { !it.isLoading }
        }

        assertFalse(
            vm.uiState.value.onboardingCompleted,
            "onboarding must be reset when graph directory is missing so user can re-select"
        )
    }

    // ─── Repeated graph switches ──────────────────────────────────────────────

    @Test
    fun isLoading_becomes_false_after_two_consecutive_graph_switches() = runBlocking {
        val vm = makeViewModel()

        vm.setGraphPath("/tmp/graph-a")
        // Wait for first load to complete before switching again
        withTimeout(5_000) { vm.uiState.first { !it.isLoading } }

        vm.setGraphPath("/tmp/graph-b")
        withTimeout(5_000) { vm.uiState.first { !it.isLoading } }

        assertFalse(vm.uiState.value.isLoading, "isLoading must be false after second graph switch")
    }

    // ─── Throwable error path ────────────────────────────────────────────────

    @Test
    fun fatalError_is_set_when_loadGraph_throws_OutOfMemoryError() = runBlocking {
        val vm = makeViewModel(graphLoader = ThrowingGraphLoader(OutOfMemoryError("simulated OOM")))

        vm.setGraphPath("/tmp/test-graph")

        withTimeout(5_000) {
            vm.uiState.first { it.fatalError != null }
        }

        val state = vm.uiState.value
        assertFalse(state.isLoading, "isLoading must be false after Throwable")
        assertNotNull(state.fatalError, "fatalError must be non-null after Throwable")
        assertTrue(
            state.fatalError.orEmpty().contains("OutOfMemoryError"),
            "fatalError should contain the class name; was: ${state.fatalError}"
        )
    }

    @Test
    fun clearFatalError_sets_fatalError_to_null() = runBlocking {
        val vm = makeViewModel(graphLoader = ThrowingGraphLoader(OutOfMemoryError("simulated OOM")))

        vm.setGraphPath("/tmp/test-graph")

        withTimeout(5_000) {
            vm.uiState.first { it.fatalError != null }
        }

        vm.clearFatalError()

        withTimeout(2_000) {
            vm.uiState.first { it.fatalError == null }
        }

        assertNull(vm.uiState.value.fatalError, "fatalError must be null after clearFatalError()")
    }

    // ─── Snackbar channel ────────────────────────────────────────────────────

    @Test
    fun sendSnackbar_delivers_message_to_snackbarEvents_collector() = runBlocking {
        val vm = makeViewModel()
        vm.sendSnackbar("Camera permission denied")
        val received = withTimeout(1_000) { vm.snackbarEvents.first() }
        assertEquals("Camera permission denied", received)
    }

    @Test
    fun sendSnackbar_second_call_delivers_second_message() = runBlocking {
        val vm = makeViewModel()
        vm.sendSnackbar("first")
        vm.sendSnackbar("second")
        val received = withTimeout(1_000) { vm.snackbarEvents.take(2).toList() }
        assertEquals(listOf("first", "second"), received)
    }

    @Test
    fun sendSnackbar_identical_messages_both_delivered() = runBlocking {
        val vm = makeViewModel()
        vm.sendSnackbar("error A")
        vm.sendSnackbar("error A")
        val received = withTimeout(1_000) { vm.snackbarEvents.take(2).toList() }
        assertEquals(listOf("error A", "error A"), received)
    }

    // ─── Cancellation resilience ─────────────────────────────────────────────

    @Test
    fun isLoading_is_false_after_scope_is_cancelled_during_load() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val pageRepo = FakePageRepository()
        val blockRepo = FakeBlockRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val graphWriter = GraphWriter(PlatformFileSystem())

        val vm = StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = FakeFileSystem(),
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = InMemorySearchRepository(),
                graphLoader = graphLoader,
                graphWriter = graphWriter,
                platformSettings = InMemorySettings(),
                scope = scope,
            )
        )

        vm.setGraphPath("/tmp/test-graph")

        // Cancel the scope immediately — simulates what DisposableEffect(viewModel) does
        // in GraphContent when the composable leaves composition
        scope.cancel()

        // After cancellation, isLoading should be false (our CancellationException fix).
        // Before the fix, CancellationException was swallowed by the generic catch and
        // isLoading could remain true.
        withTimeout(2_000) {
            vm.uiState.first { !it.isLoading }
        }

        assertFalse(vm.uiState.value.isLoading, "isLoading must be false after scope cancellation")
    }

    /**
     * A [GraphLoaderPort] that throws [throwable] from [loadGraphProgressive], simulating
     * fatal errors such as [OutOfMemoryError] during graph loading.
     */
    private class ThrowingGraphLoader(private val throwable: Throwable) : GraphLoaderPort {
        override fun setActivePageUuids(uuids: StateFlow<Set<String>>?) = Unit
        override fun setUnsavedPageUuids(uuids: StateFlow<Set<String>>?) = Unit
        override val externalFileChanges: SharedFlow<ExternalFileChange> = MutableSharedFlow()
        override val writeErrors: SharedFlow<WriteError> = MutableSharedFlow()
        override fun setCryptoLayer(layer: CryptoLayer?) = Unit
        override fun closeAndClearCryptoLayer() = Unit
        override suspend fun loadGraphProgressive(
            graphPath: String,
            immediateJournalCount: Int,
            onProgress: (String) -> Unit,
            onPhase1Complete: () -> Unit,
            onFullyLoaded: () -> Unit,
        ) { throw throwable }
        override suspend fun indexRemainingPages(onProgress: (String) -> Unit) = Unit
        override suspend fun loadPageByName(pageName: PageName): Page? = null
        override suspend fun loadFullPage(pageUuid: String, force: Boolean) = Unit
        override fun cancelBackgroundWork() = Unit
        override suspend fun parseAndSavePage(
            filePath: FilePath,
            content: String,
            mode: ParseMode,
            priority: DatabaseWriteActor.Priority,
        ) = Unit
    }
}
