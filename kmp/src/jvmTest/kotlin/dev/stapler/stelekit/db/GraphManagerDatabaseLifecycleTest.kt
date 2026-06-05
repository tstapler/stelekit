package dev.stapler.stelekit.db

import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Integration regression tests for the Android startup crash:
 * "attempt to re-open an already-closed object: SQLiteDatabase"
 *
 * Root cause: GraphManager.shutdown() and switchGraph() closed the SQLite driver BEFORE
 * nulling _activeRepositorySet. Compose LaunchedEffect coroutines collecting repository
 * Flows saw a non-null RepositorySet but the underlying DB was already closed, causing
 * IllegalStateException to propagate uncaught to the main thread → app crash.
 *
 * Fix A (GraphManager): null _activeRepositorySet before calling factory.close().
 * Fix B (Repositories): .catch on Flows converts DB exceptions to Either.Left.
 *
 * These tests exercise the full seam between GraphManager and repository Flows.
 * They must NOT mock the seam itself — both components are real.
 */
class GraphManagerDatabaseLifecycleTest {

    private class StubSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
    }

    private class StubFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/test"
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

    // TC-GM-LIFECYCLE-001
    @Test
    fun `shutdown does not crash active Flow collectors and nulls activeRepositorySet`() = runBlocking {
        val graphManager = GraphManager(
            platformSettings = StubSettings(),
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.SQLDELIGHT,
        )

        // Add a graph — creates real SQLite database
        graphManager.addGraph("/test")

        // Wait for the real repo set to be available
        val repoSet = graphManager.activeRepositorySet.filterNotNull().first()

        var collectionCrashed = false
        val collectedValues = mutableListOf<Any>()

        // Simulate a Compose LaunchedEffect: collect from a repository Flow
        val collectJob = launch(Dispatchers.Default) {
            try {
                repoSet.pageRepository.getAllPages().collect { result ->
                    collectedValues.add(result)
                }
            } catch (e: Throwable) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    collectionCrashed = true
                }
            }
        }

        yield() // let the first emission happen

        // Act: shutdown closes the DB — pre-fix this was: close() THEN null.
        // With an active collector, close() triggered the crash.
        graphManager.shutdown()

        collectJob.cancelAndJoin()

        // Assert 1: flow collector never threw an uncaught exception.
        // Pre-fix (no .catch): collectionCrashed = true because IllegalStateException
        // escaped the flow and hit the coroutine's uncaught handler.
        assertFalse(
            collectionCrashed,
            "Repository Flow collection crashed with an uncaught exception during shutdown. " +
                "Pre-fix: IllegalStateException: attempt to re-open an already-closed object. " +
                "Post-fix: .catch converts DB errors to Either.Left; flow terminates cleanly."
        )

        // Assert 2: activeRepositorySet is null after shutdown.
        // This is the observable invariant: callers must see null before the DB is gone.
        assertNull(
            graphManager.activeRepositorySet.value,
            "activeRepositorySet must be null after shutdown()."
        )
    }

    // TC-GM-LIFECYCLE-002
    @Test
    fun `switchGraph does not crash collectors from previous graph`() = runBlocking {
        val graphManager = GraphManager(
            platformSettings = StubSettings(),
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.SQLDELIGHT,
        )

        val graph1Id = graphManager.addGraph("/test/graph1")
        val graph2Id = graphManager.graphIdFromPath("/test/graph2").also {
            graphManager.addGraph("/test/graph2")
        }

        // Return to graph1 so we can switch away from it
        graphManager.switchGraph(graph1Id)
        graphManager.awaitPendingMigration()

        val repoSet = graphManager.activeRepositorySet.filterNotNull().first()

        var collectionCrashed = false
        val collectJob = launch(Dispatchers.Default) {
            try {
                repoSet.pageRepository.getAllPages().collect { }
            } catch (e: Throwable) {
                if (e !is kotlinx.coroutines.CancellationException) collectionCrashed = true
            }
        }

        yield()

        // Switch away — closes graph1's DB while collector is still running
        graphManager.switchGraph(graph2Id)
        collectJob.cancelAndJoin()

        assertFalse(
            collectionCrashed,
            "Switching graphs must not crash active collectors from the previous graph. " +
                "Pre-fix: IllegalStateException from closed DB. Post-fix: Either.Left emitted."
        )

        graphManager.shutdown()
    }
}
