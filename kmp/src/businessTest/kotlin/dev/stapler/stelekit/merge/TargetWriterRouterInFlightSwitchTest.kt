package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphInitHooks
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.MoveInProgressFlag
import dev.stapler.stelekit.db.ReadyGraph
import dev.stapler.stelekit.db.RegistryGraphLocator
import dev.stapler.stelekit.error.DomainError
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TargetWriterRouterInFlightSwitchTest {
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

    private class Hooks : GraphInitHooks {
        var beforeCreate: suspend (GraphId) -> Unit = {}
        var onClose: suspend (GraphId?) -> Unit = {}
        override suspend fun beforeCreateRepositorySet(id: GraphId) = beforeCreate(id)
        override suspend fun beforeFactoryClose(owner: GraphId?) = onClose(owner)
    }

    private class FakeWriter(val label: String) : TargetWriter {
        override suspend fun readExisting(page: PageKey): Either<DomainError, MergePage?> = null.right()
        override suspend fun write(page: PageKey, merged: MergePage): Either<DomainError, WriteOutcome> = WriteOutcome.Unchanged.right()
        override suspend fun deletePageFile(page: PageKey, expectedHash: String): Either<DomainError, Unit> = Unit.right()
        override suspend fun fileHash(page: PageKey): Either<DomainError, String?> = null.right()
        override suspend fun removeBlocks(
            page: PageKey,
            uuids: Set<String>,
            expectedContentHashes: Map<String, String>,
        ): Either<DomainError, RemoveReport> = RemoveReport(emptySet(), emptySet(), emptySet()).right()
    }

    private val managers = mutableListOf<GraphManager>()
    private val usedPairs = MutableStateFlow<List<ReadyGraph>>(emptyList())
    private val lockWaits = MutableStateFlow<List<Pair<GraphId, Long>>>(emptyList())

    @AfterTest
    fun cleanup() {
        managers.forEach { it.shutdown() }
        managers.clear()
    }

    private fun newManager(hooks: Hooks = Hooks(), lockTimeout: Duration = 10.seconds): GraphManager {
        val graphs = listOf(a, b, c).map { GraphInfo(id = it, path = "/data/${it.value}", displayName = it.value, addedAt = 0L) }
        val settings = MapSettings()
        settings.putString("graph_registry", Json.encodeToString(GraphRegistry(activeGraphId = a, graphs = graphs)))
        return GraphManager(
            platformSettings = settings,
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
            lockAcquireTimeout = lockTimeout,
            initHooks = hooks,
        ).also { managers += it }
    }

    private fun router(m: GraphManager, offGraphWrites: Boolean = true) = TargetWriterRouter(
        graphManager = m,
        locator = RegistryGraphLocator(m.graphRegistry),
        capabilities = TargetWriterCapabilities(platformSupportsOffGraphWrite = offGraphWrites),
        activeWriterFor = { ready -> usedPairs.update { it + ready }; FakeWriter("active") },
        offGraphWriterFor = { FakeWriter("off") },
        onLockWaitMs = { id, ms -> lockWaits.update { it + (id to ms) } },
    )

    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(60.seconds) { block(this) } }
    }

    private val label: suspend (TargetWriter) -> Either<DomainError, String> = { w -> (w as FakeWriter).label.right() }

    @Test
    fun `predicate uses the ready pair - active target gets active writer, inactive gets off-graph`() = realTime {
        val m = newManager()
        m.awaitPendingMigration()
        val r = router(m)
        assertEquals("active", (r.withWriter(a, label) as Either.Right).value)
        assertEquals("off", (r.withWriter(b, label) as Either.Right).value)
        assertEquals(a, usedPairs.value.single().id)
        assertTrue(lockWaits.value.isNotEmpty(), "lock_wait_ms emitted")
    }

    @Test
    fun `capabilities refusal returns WriteRefused and never runs the block`() = realTime {
        val m = newManager()
        m.awaitPendingMigration()
        var ran = false
        val res = router(m, offGraphWrites = false).withWriter(b) { ran = true; "x".right() }
        val err = (res as Either.Left).value
        assertIs<WriteRefusedReason.Unwritable>(assertIs<DomainError.MergeError.WriteRefused>(err).reason)
        assertFalse(ran)
    }

    @Test
    fun `unknown target is NotFound`() = realTime {
        val m = newManager()
        m.awaitPendingMigration()
        val res = router(m).withWriter(GraphId("gone"), label)
        assertIs<DomainError.DatabaseError.NotFound>((res as Either.Left).value)
    }

    @Test
    fun `registry says active but init unfinished - router awaits holding no lock, then uses active writer`() = realTime {
        val gate = CompletableDeferred<Unit>()
        val hooks = Hooks().apply { beforeCreate = { id -> if (id == b) gate.await() } }
        val m = newManager(hooks)
        m.awaitPendingMigration()
        m.switchGraph(b)
        assertEquals(b, m.graphRegistry.value.activeGraphId)
        val job = async { router(m).withWriter(b, label) }
        delay(300)
        assertFalse(job.isCompleted)
        assertEquals("switchGraph($b)", m.graphWriteLock.holderLabel(b), "only the init coroutine holds lock(B); router holds none")
        gate.complete(Unit)
        assertEquals("active", (job.await() as Either.Right).value)
        assertEquals(b, usedPairs.value.single().id)
    }

    @Test
    fun `switch to C during the await falls back to the file writer for B, never C's set`() = realTime {
        val gate = CompletableDeferred<Unit>()
        val hooks = Hooks().apply { beforeCreate = { id -> if (id == b) gate.await() } }
        val m = newManager(hooks)
        m.awaitPendingMigration()
        m.switchGraph(b)
        val job = async { router(m).withWriter(b, label) }
        delay(200)
        m.switchGraph(c)
        gate.complete(Unit)
        assertEquals("off", (job.await() as Either.Right).value)
        assertTrue(usedPairs.value.none { it.id != b })
    }

    @Test
    fun `inactive to active mid-run - init waits for the batch, later batches use active writer`() = realTime {
        val m = newManager()
        m.awaitPendingMigration()
        val r = router(m)
        val inBatch = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val run = async {
            r.withWriter(b) { w -> inBatch.complete(Unit); finish.await(); (w as FakeWriter).label.right() }
        }
        inBatch.await()
        m.switchGraph(b)
        delay(300)
        assertTrue(m.readyGraphId != b, "init of B must wait for the in-flight batch")
        finish.complete(Unit)
        assertEquals("off", (run.await() as Either.Right).value)
        m.awaitPendingMigration()
        assertEquals("active", (r.withWriter(b, label) as Either.Right).value)
    }

    @Test
    fun `active to inactive mid-run - injected close is retryable and the router re-decides to the file writer`() = realTime {
        val m = newManager()
        m.awaitPendingMigration()
        val r = router(m)
        var calls = 0
        val res = r.withWriter(a) { w ->
            calls++
            if (calls == 1) {
                m.switchGraph(b) // A's teardown begins; its factory close waits on lock(A)
                DomainError.MergeError.Retryable("channel closed").left()
            } else {
                (w as FakeWriter).label.right()
            }
        }
        assertEquals("off", (res as Either.Right).value)
        assertEquals(2, calls)
    }

    @Test
    fun `holder keeps writing after the close timeout - the actor is stopped, the batch gets retryable and the router re-decides`() = realTime {
        val closed = CompletableDeferred<Unit>()
        val m = newManager(Hooks().apply { onClose = { if (it == a) closed.complete(Unit) } }, lockTimeout = 300.milliseconds)
        m.awaitPendingMigration()
        val pair = assertNotNull(m.readyGraph.value)
        var calls = 0
        var stoppedWhileHeld = false
        val res = router(m).withWriter(a) { w ->
            calls++
            if (calls == 1) {
                m.switchGraph(b)
                closed.await() // the 300 ms acquire timeout elapsed: A's factory closes while this batch holds lock(A)
                stoppedWhileHeld = assertNotNull(pair.repoSet.writeActor).isStopped
                DomainError.MergeError.Retryable("graph closed").left()
            } else {
                (w as FakeWriter).label.right()
            }
        }
        assertTrue(stoppedWhileHeld, "writes through A's actor must fail fast once its factory is closed")
        assertEquals("off", (res as Either.Right).value)
        assertEquals(2, calls)
    }

    @Test
    fun `a target being relocated is refused as retryable instead of written through the file writer`() = realTime {
        val m = newManager()
        m.awaitPendingMigration()
        val r = router(m)
        var ran = false
        MoveInProgressFlag.setMoveInProgress(b.value, true)
        try {
            val res = r.withWriter(b) { ran = true; "x".right() }
            assertIs<DomainError.MergeError.Retryable>((res as Either.Left).value)
            assertFalse(ran, "no writer may touch the directory being moved")
        } finally {
            MoveInProgressFlag.setMoveInProgress(b.value, false)
        }
        assertEquals("off", (r.withWriter(b, label) as Either.Right).value)
    }

    @Test
    fun `retryable forever exhausts MAX_ROUTER_ATTEMPTS and returns a retryable Left`() = realTime {
        val m = newManager()
        m.awaitPendingMigration()
        var calls = 0
        val res = router(m).withWriter<String>(a) { calls++; DomainError.MergeError.Retryable("closed").left() }
        assertIs<DomainError.MergeError.Retryable>((res as Either.Left).value)
        assertEquals(MAX_ROUTER_ATTEMPTS, calls)
    }

    @Test
    fun `racing switches never hand the router an (id, set) pair for another graph`() = realTime {
        val m = newManager()
        m.awaitPendingMigration()
        val r = router(m)
        val writers = async {
            repeat(40) {
                r.withWriter(b) { w -> assertNotNull(w); "ok".right() }
                delay(2)
            }
        }
        repeat(15) { m.switchGraph(b); delay(3); m.switchGraph(c); delay(3); m.switchGraph(a) }
        writers.await()
        assertTrue(usedPairs.value.all { it.id == b }, "active writer only ever built from B's own pair")
    }
}
