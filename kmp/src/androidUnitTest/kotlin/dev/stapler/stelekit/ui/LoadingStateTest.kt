package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.repository.JournalService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TC-008, TC-009, TC-010: Tests for the loading state machine in StelekitViewModel.
 *
 * Uses a FakeFileSystem where directoryExists=false and createDirectory=false to trigger the
 * fast-failure path (graph dir unreachable). This resets isLoading quickly without real file I/O.
 *
 * The ViewModel runs in its own independent scope (not the runTest scope) to avoid
 * JobCancellationException when the test scope advances.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LoadingStateTest {

    private class FakeSettings : Settings {
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun putBoolean(key: String, value: Boolean) {}
        override fun getString(key: String, defaultValue: String) = defaultValue
        override fun putString(key: String, value: String) {}
    }

    private class FakeFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/fake/graph"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = false
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = false
        override fun deleteFile(path: String) = false
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    private val vmScopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() {
        vmScopes.forEach { it.cancel() }
        vmScopes.clear()
    }

    private fun makeViewModel(): StelekitViewModel {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val searchRepo = InMemorySearchRepository(pageRepo, blockRepo)
        val fileSystem = FakeFileSystem()
        val graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo)
        val graphWriter = GraphWriter(fileSystem)
        val journalService = JournalService(pageRepo, blockRepo)
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        vmScopes += vmScope
        return StelekitViewModel(
            fileSystem = fileSystem,
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            searchRepository = searchRepo,
            graphLoader = graphLoader,
            graphWriter = graphWriter,
            platformSettings = FakeSettings(),
            scope = vmScope,
            journalService = journalService,
        )
    }

    // TC-008: isLoading starts true when ViewModel is created (before any graph is loaded)
    @Test
    fun `isLoading is true on ViewModel initialization`() = runTest {
        val vm = makeViewModel()
        assertTrue(vm.uiState.value.isLoading, "isLoading must start true before any graph loads")
    }

    // TC-009: isLoading becomes false after loadGraph completes (fast-fail: dir unreachable)
    @Test
    fun `isLoading becomes false after loadGraph completes`() = runTest {
        val vm = makeViewModel()
        vm.loadGraph("/fake/nonexistent/path")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading, "isLoading must be false after loadGraph completes")
    }

    // TC-010: statusMessage changes from initial value during loadGraph
    @Test
    fun `statusMessage is updated during loadGraph`() = runTest {
        val vm = makeViewModel()
        vm.loadGraph("/fake/path")
        advanceUntilIdle()
        // After fast-fail (dir cannot be created), statusMessage transitions away from "Ready".
        val finalMessage = vm.uiState.value.statusMessage
        assertFalse(finalMessage == "Ready", "statusMessage must change from initial 'Ready' during loadGraph")
    }
}
