package dev.stapler.stelekit.db

import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

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
        override fun containsKey(key: String) = store.containsKey(key)
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

        val repoSet = graphManager.openGraph("/test")

        var collectionCrashed = false
        val collectedValues = mutableListOf<Any>()

        // Simulate a Compose LaunchedEffect: collect from a repository Flow
        val collectJob = launch(Dispatchers.Default) {
            try {
                repoSet.pageRepository.getPages(50, 0).collect { result ->
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

        val graph2Id = graphManager.addGraph("/test/graph2")
        val repoSet = graphManager.openGraph("/test/graph1")

        var collectionCrashed = false
        val collectJob = launch(Dispatchers.Default) {
            try {
                repoSet.pageRepository.getPages(50, 0).collect { }
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

    // TC-GM-LIFECYCLE-003
    @Test
    fun `switchGraph with same active id is idempotent once initialization completes`() = runBlocking {
        // Regression for the v0.38.4 crash on returning-user devices.
        // init{} calls switchGraph(activeId); then StelekitApp's LaunchedEffect calls it again.
        // On fast devices/emulators DB init finishes before the LaunchedEffect fires, so
        // _activeRepositorySet is non-null and the (a) branch of the guard fires.
        val graphManager = GraphManager(
            platformSettings = StubSettings(),
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.SQLDELIGHT,
        )

        val repoSet = graphManager.openGraph("/test")
        val graphId = graphManager.getActiveGraphId()!!

        assertNotNull(graphManager.activeRepositorySet.value)

        graphManager.switchGraph(graphId)
        yield()

        assertNotNull(
            graphManager.activeRepositorySet.value,
            "switchGraph(sameId) after init must not null out activeRepositorySet."
        )
        assertSame(
            repoSet,
            graphManager.activeRepositorySet.value,
            "The same RepositorySet must be reused; no new DB connection should be opened."
        )

        graphManager.shutdown()
    }

    // TC-GM-LIFECYCLE-004
    @Test
    fun `switchGraph with same active id is idempotent while initialization is still in progress`() = runBlocking {
        // Regression for the crash on real devices with large graphs (e.g. 8030 pages).
        // DB init is slow enough that the LaunchedEffect fires before _activeRepositorySet
        // becomes non-null. The v0.39.0 guard only checked _activeRepositorySet != null (branch a),
        // so the second call would cancel the running init scope → crash.
        // The fix adds branch (b): also return early if activeGraphJobs already contains the id.
        val blockingJob = kotlinx.coroutines.CompletableDeferred<Unit>()
        val graphManager = GraphManager(
            platformSettings = StubSettings(),
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.SQLDELIGHT,
            preFlightJob = blockingJob, // holds DB init open so second call arrives first
        )

        val graphId = graphManager.addGraph("/test")
        graphManager.switchGraph(graphId) // first call — init blocked by preFlightJob

        // _activeRepositorySet is still null; init is in progress
        assertNull(graphManager.activeRepositorySet.value)

        // Simulate the LaunchedEffect calling switchGraph again before init completes.
        // Pre-fix (b): this cancelled the first init scope and the DB never opened.
        graphManager.switchGraph(graphId)
        yield()

        // Unblock init — the original scope must still be running and complete normally.
        blockingJob.complete(Unit)
        graphManager.awaitPendingMigration()

        assertNotNull(
            graphManager.activeRepositorySet.value,
            "switchGraph(sameId) while init is in progress must not cancel the running init scope. " +
                "Pre-fix: second call cancelled the scope; DB never opened; app crashed."
        )

        graphManager.shutdown()
    }

    // TC-GM-LIFECYCLE-005
    @Test
    fun `switchGraph to a new graph succeeds after a previous graph's scope was cancelled`() = runBlocking {
        // Regression for the WASM "stuck on Initializing…" bug.
        //
        // Pre-fix: graphScope = CoroutineScope(coroutineScope.coroutineContext) shared the
        // manager's SupervisorJob. Cancelling graphScope1 via `previousGraphScope?.cancel()` at
        // the top of the SECOND switchGraph call killed coroutineScope itself. The third
        // `launch { ... }` in the new graphScope ran in an already-cancelled context — the
        // coroutine body never executed, so `deferred.complete(Unit)` was never called, and
        // `awaitPendingMigration()` hung forever.
        //
        // Post-fix: each graphScope uses an independent SupervisorJob(), so cancelling one
        // graph's scope has no effect on the manager scope or subsequent graph scopes.
        val graphManager = GraphManager(
            platformSettings = StubSettings(),
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.SQLDELIGHT,
        )

        // Unique suffix avoids stale .db files from previous test runs (JDBC creates real files).
        val runId = System.nanoTime()

        // First graph — completes full init cycle (scope is created, then cancelled on switch).
        graphManager.openGraph("/test/graph1-$runId")

        // Second graph — must also complete init despite graph1's scope being cancelled.
        // Pre-fix: this hangs forever at awaitPendingMigration().
        graphManager.openGraph("/test/graph2-$runId")

        assertNotNull(
            graphManager.activeRepositorySet.value,
            "openGraph() after a fully-completed previous graph must not hang at migration await. " +
                "Pre-fix: CoroutineScope(coroutineScope.coroutineContext) shared the Job — " +
                "cancelling graph1's scope killed the manager scope; graph2's launch never ran."
        )

        graphManager.shutdown()
    }
}
