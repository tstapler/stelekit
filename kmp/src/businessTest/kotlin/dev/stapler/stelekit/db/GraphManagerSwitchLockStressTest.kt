package dev.stapler.stelekit.db

import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Exercises the GraphWriteLock seam in GraphManager.switchGraph. Real time, every case under withTimeout. */
class GraphManagerSwitchLockStressTest {
    private val a = GraphId("aaaaaaaaaaaaaaaa")
    private val b = GraphId("bbbbbbbbbbbbbbbb")
    private val c = GraphId("cccccccccccccccc")

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    /** Hooks with per-test behaviour plus a log of factory closes. */
    private class Hooks : GraphInitHooks {
        var createDelay: Duration = Duration.ZERO
        var migration: suspend (GraphId) -> Unit = {}

        /** Simulates a driver open that ignores cancellation (blocking IO) for this one graph. */
        var uncancellableCreate: Pair<GraphId, Duration>? = null
        private val closedLog = kotlinx.coroutines.flow.MutableStateFlow<List<GraphId?>>(emptyList())
        val closed: List<GraphId?> get() = closedLog.value
        override suspend fun beforeCreateRepositorySet(id: GraphId) {
            if (createDelay > Duration.ZERO) delay(createDelay)
            uncancellableCreate?.takeIf { it.first == id }?.let { withContext(kotlinx.coroutines.NonCancellable) { delay(it.second) } }
        }
        override suspend fun duringMigration(id: GraphId) = migration(id)
        override suspend fun beforeFactoryClose(owner: GraphId?) { closedLog.update { it + owner } }
    }

    private val managers = mutableListOf<GraphManager>()

    @AfterTest
    fun cleanup() {
        managers.forEach { it.shutdown() }
        managers.clear()
    }

    private fun newManager(hooks: Hooks, timeout: Duration = 10.seconds): GraphManager {
        val graphs = listOf(a, b, c).map {
            GraphInfo(id = it, path = "/data/${it.value}", displayName = it.value, addedAt = 0L)
        }
        val settings = MapSettings()
        settings.putString(
            "graph_registry",
            Json.encodeToString(GraphRegistry(activeGraphId = a, graphs = graphs)),
        )
        return GraphManager(
            platformSettings = settings,
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
            lockAcquireTimeout = timeout,
            initHooks = hooks,
        ).also { managers += it }
    }

    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(60.seconds) { block(this) } }
    }

    /** Cancelled earlier init coroutines unwind asynchronously; a leak means it never converges. */
    private suspend fun assertAllUnlockedEventually(m: GraphManager) {
        withTimeout(10.seconds) { while (listOf(a, b, c).any { m.graphWriteLock.isLocked(it) }) delay(5) }
        listOf(a, b, c).forEach { assertFalse(m.graphWriteLock.isLocked(it), "lock($it) leaked") }
    }

    private suspend fun awaitReady(m: GraphManager, id: GraphId) {
        while (m.readyGraphId != id) delay(5)
    }

    @Test
    fun `50 rapid B-C-B switches settle on B with no lock leaked`() = realTime {
        val m = newManager(Hooks())
        m.awaitPendingMigration()
        repeat(50) {
            m.switchGraph(b); m.switchGraph(c); m.switchGraph(b)
        }
        m.switchGraph(b)
        assertNotNull(m.awaitPendingMigration())
        assertEquals(b, m.readyGraphId)
        assertEquals(b, m.readyGraph.value?.id)
        assertAllUnlockedEventually(m)
    }

    @Test
    fun `slow driver creation across B then C completes without deadlock`() = realTime {
        val hooks = Hooks().apply { createDelay = 150.milliseconds }
        val m = newManager(hooks)
        m.awaitPendingMigration()
        m.switchGraph(b)
        m.switchGraph(c)
        assertNotNull(m.awaitPendingMigration())
        assertEquals(c, m.readyGraphId)
        assertAllUnlockedEventually(m)
    }

    @Test
    fun `writer waits for paused migration even though readyGraphId is already set`() = realTime {
        val gate = CompletableDeferred<Unit>()
        val hooks = Hooks().apply { migration = { id -> if (id == b) gate.await() } }
        val m = newManager(hooks)
        m.awaitPendingMigration()
        m.switchGraph(b)
        awaitReady(m, b) // readiness precedes migration completion
        var wrote = false
        val writer = async { m.graphWriteLock.withLock(b) { wrote = true } }
        delay(200)
        assertFalse(wrote, "writer must block until migration releases lock(B)")
        gate.complete(Unit)
        writer.await()
        assertTrue(wrote)
        assertNotNull(m.awaitPendingMigration())
    }

    @Test
    fun `throwing migration releases the lock completes the deferred and next switch succeeds`() = realTime {
        val hooks = Hooks().apply { migration = { id -> if (id == b) throw IllegalStateException("boom") } }
        val m = newManager(hooks)
        m.awaitPendingMigration()
        m.switchGraph(b)
        m.awaitPendingMigration() // must not hang
        assertFalse(m.graphWriteLock.isLocked(b))
        m.switchGraph(c)
        assertNotNull(m.awaitPendingMigration())
        assertEquals(c, m.readyGraphId)
    }

    @Test
    fun `merge batch holding lock A while switching to B degrades open after the timeout`() = realTime {
        val hooks = Hooks()
        val m = newManager(hooks, timeout = 300.milliseconds)
        m.awaitPendingMigration()
        val release = CompletableDeferred<Unit>()
        val merge = async { m.graphWriteLock.withLock(a, label = "merge") { release.await() } }
        while (!m.graphWriteLock.isLocked(a)) delay(5)

        m.switchGraph(b)
        assertNotNull(m.awaitPendingMigration(), "open must not wait for the merge")
        assertEquals(b, m.readyGraphId)
        assertTrue(m.graphWriteLock.isLocked(a), "merge still holds lock(A)")
        release.complete(Unit)
        merge.await()
    }

    @Test
    fun `B then C with lock A held closes A's factory once, after release, under A`() = realTime {
        val hooks = Hooks()
        val m = newManager(hooks)
        m.awaitPendingMigration()
        val release = CompletableDeferred<Unit>()
        val merge = async { m.graphWriteLock.withLock(a, label = "merge") { release.await() } }
        while (!m.graphWriteLock.isLocked(a)) delay(5)

        m.switchGraph(b)
        m.switchGraph(c)
        delay(300)
        assertTrue(hooks.closed.isEmpty(), "A's close must wait on lock(A), got ${hooks.closed}")
        release.complete(Unit)
        merge.await()
        assertNotNull(m.awaitPendingMigration())
        withTimeout(10.seconds) { while (hooks.closed.isEmpty()) delay(10) }
        delay(200)
        assertEquals(listOf<GraphId?>(a), hooks.closed, "exactly one close, owned by A")
        assertEquals(c, m.readyGraphId)
    }

    @Test
    fun `B init cancelled by a B-to-C switch never publishes B and its factory is closed once`() = realTime {
        val hooks = Hooks().apply { uncancellableCreate = b to 400.milliseconds }
        val m = newManager(hooks)
        m.awaitPendingMigration()
        val published = kotlinx.coroutines.flow.MutableStateFlow<List<GraphId>>(emptyList())
        val watcher = launch { m.readyGraph.collect { r -> if (r != null) published.update { it + r.id } } }

        m.switchGraph(b)
        delay(100) // B is parked inside its uncancellable driver open
        m.switchGraph(c)
        assertNotNull(m.awaitPendingMigration())
        assertEquals(c, m.readyGraphId)
        delay(800) // B's open finishes and reaches its publish point
        watcher.cancel()

        assertEquals(c, m.readyGraphId, "stale B publish must not overwrite C")
        assertEquals(c, m.readyGraph.value?.id)
        assertFalse(b in published.value, "ReadyGraph(B) was published: ${published.value}")
        assertEquals(1, hooks.closed.count { it == b }, "B's abandoned factory closed exactly once: ${hooks.closed}")
        assertAllUnlockedEventually(m)
    }

    @Test
    fun `relocation teardown waits for a batch holding lock A and closes A's factory once under it`() = realTime {
        val hooks = Hooks()
        val m = newManager(hooks)
        m.awaitPendingMigration()
        val release = CompletableDeferred<Unit>()
        val merge = async { m.graphWriteLock.withLock(a, label = "merge") { release.await() } }
        while (!m.graphWriteLock.isLocked(a)) delay(5)

        val teardown = async { m.tearDownAndCloseActiveGraph(a) }
        delay(300)
        assertTrue(hooks.closed.isEmpty(), "close must wait on lock(A), got ${hooks.closed}")
        assertFalse(teardown.isCompleted)
        release.complete(Unit)
        merge.await()
        teardown.await()

        assertEquals(listOf<GraphId?>(a), hooks.closed)
        assertAllUnlockedEventually(m)
    }

    @Test
    fun `readyGraph pair matches activeRepositorySet after racing switches`() = realTime {
        val m = newManager(Hooks())
        m.awaitPendingMigration()
        repeat(20) { m.switchGraph(b); delay(3); m.switchGraph(c); delay(3); m.switchGraph(a) }
        m.awaitPendingMigration()
        val final = m.readyGraph.value
        assertNotNull(final)
        assertEquals(m.activeRepositorySet.value, final.repoSet)
        assertEquals(a, final.id)
    }
}
