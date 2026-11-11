package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphInitHooks
import dev.stapler.stelekit.db.GraphManager
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
import kotlin.time.Duration.Companion.seconds

/** `TargetWriterRouter.replaceOwnedPage` under the one `GraphWriteLock`, with a concurrent `switchGraph`. */
class OwnedReplaceLockOrderTest {
    private val a = GraphId("aaaaaaaaaaaaaaaa")
    private val b = GraphId("bbbbbbbbbbbbbbbb")
    private val c = GraphId("cccccccccccccccc")
    private val page = PageKey("Digest")
    private val content = MergePage("Digest", blocks = emptyList())

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
        override suspend fun beforeCreateRepositorySet(id: GraphId) = beforeCreate(id)
        override suspend fun beforeFactoryClose(owner: GraphId?) {}
    }

    private open class PlainWriter : TargetWriter {
        override suspend fun readExisting(page: PageKey): Either<DomainError, MergePage?> = null.right()
        override suspend fun write(page: PageKey, merged: MergePage): Either<DomainError, WriteOutcome> = WriteOutcome.Unchanged.right()
        override suspend fun deletePageFile(page: PageKey, expectedHash: String): Either<DomainError, Unit> = Unit.right()
        override suspend fun fileHash(page: PageKey): Either<DomainError, String?> = null.right()
        override suspend fun removeBlocks(page: PageKey, uuids: Set<String>, expectedContentHashes: Map<String, String>) =
            RemoveReport(emptySet(), emptySet(), emptySet()).right()
    }

    private class OwnedFake(private val lockHolder: (GraphId) -> String?, val graph: GraphId) : PlainWriter(), OwnedPageWriter {
        var lockSeen: String? = null
        override suspend fun replaceOwnedPage(page: PageKey, owned: MergePage, expectedPriorHash: String?): Either<DomainError, WriteOutcome> {
            lockSeen = lockHolder(graph)
            return WriteOutcome.Created("/p", "h").right()
        }
    }

    private val managers = mutableListOf<GraphManager>()
    private val activeUses = MutableStateFlow<List<GraphId>>(emptyList())
    private val offGraphRuns = MutableStateFlow(0)

    @AfterTest
    fun cleanup() {
        managers.forEach { it.shutdown() }
        managers.clear()
    }

    private fun newManager(hooks: Hooks = Hooks()): GraphManager {
        val graphs = listOf(a, b, c).map { GraphInfo(id = it, path = "/data/${it.value}", displayName = it.value, addedAt = 0L) }
        val settings = MapSettings()
        settings.putString("graph_registry", Json.encodeToString(GraphRegistry(activeGraphId = a, graphs = graphs)))
        return GraphManager(
            platformSettings = settings, driverFactory = DriverFactory(), fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY, lockAcquireTimeout = 10.seconds, initHooks = hooks,
        ).also { managers += it }
    }

    private fun router(m: GraphManager, activeWriter: (ReadyGraph) -> TargetWriter) = TargetWriterRouter(
        graphManager = m,
        locator = RegistryGraphLocator(m.graphRegistry),
        capabilities = TargetWriterCapabilities(platformSupportsOffGraphWrite = true),
        activeWriterFor = { ready -> activeUses.update { it + ready.id }; activeWriter(ready) },
        offGraphWriterFor = { offGraphRuns.update { n -> n + 1 }; PlainWriter() },
        retryBackoff = {},
    )

    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(60.seconds) { block(this) } }
    }

    @Test
    fun theActiveWriterIsReachedUnderTheTargetsLock() = realTime {
        val m = newManager()
        m.awaitPendingMigration()
        lateinit var fake: OwnedFake
        val r = router(m) { ready -> OwnedFake({ id -> m.graphWriteLock.holderLabel(id) }, ready.id).also { fake = it } }
        val result = r.replaceOwnedPage(a, page, content, null)
        assertIs<WriteOutcome.Created>((result as Either.Right).value)
        assertNotNull(fake.lockSeen, "guard and replace run while the router holds GraphWriteLock(target)")
    }

    @Test
    fun aWriterThatIsNotOwnedCapableIsRetryableNeverNotOwned() = realTime {
        val m = newManager()
        m.awaitPendingMigration()
        val busyActive = router(m) { PlainWriter() } // stands in for RefusingWriter and any wrapper
        val onActive = busyActive.replaceOwnedPage(a, page, content, null)
        assertIs<DomainError.MergeError.Retryable>((onActive as Either.Left).value)
        val onOffGraph = busyActive.replaceOwnedPage(b, page, content, null) // inactive target: the file writer
        assertIs<DomainError.MergeError.Retryable>((onOffGraph as Either.Left).value)
        assertTrue(offGraphRuns.value >= 1)
    }

    @Test
    fun aSwitchAwayDuringTheAwaitYieldsRetryableAndNeverWritesTheWrongGraph() = realTime {
        val gate = CompletableDeferred<Unit>()
        val hooks = Hooks().apply { beforeCreate = { id -> if (id == b) gate.await() } }
        val m = newManager(hooks)
        m.awaitPendingMigration()
        val r = router(m) { ready -> OwnedFake({ id -> m.graphWriteLock.holderLabel(id) }, ready.id) }
        m.switchGraph(b)
        val job = async { r.replaceOwnedPage(b, page, content, null) }
        delay(300)
        assertFalse(job.isCompleted)
        assertEquals("switchGraph($b)", m.graphWriteLock.holderLabel(b), "the router awaits holding no lock; only init holds lock(B)")
        m.switchGraph(c)
        gate.complete(Unit)
        val result = job.await()
        assertIs<DomainError.MergeError.Retryable>((result as Either.Left).value)
        assertTrue(activeUses.value.none { it != b }, "no owned write ever used another graph's repositories: ${activeUses.value}")
    }

    @Test
    fun anInFlightRetryableFromTheWriterIsReDecidedAndExhaustsAsRetryable() = realTime {
        val m = newManager()
        m.awaitPendingMigration()
        var calls = 0
        val w = object : PlainWriter(), OwnedPageWriter {
            override suspend fun replaceOwnedPage(page: PageKey, owned: MergePage, expectedPriorHash: String?): Either<DomainError, WriteOutcome> {
                calls++
                return DomainError.MergeError.Retryable("busy").left()
            }
        }
        val result = router(m) { w }.replaceOwnedPage(a, page, content, null)
        assertIs<DomainError.MergeError.Retryable>((result as Either.Left).value)
        assertEquals(MAX_ROUTER_ATTEMPTS, calls)
    }
}
