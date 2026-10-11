@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.RegistryGraphLocator
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.SqlDelightBlockRepository
import dev.stapler.stelekit.repository.SqlDelightPageRepository
import dev.stapler.stelekit.repository.SqlDelightSearchRepository
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.fakefilesystem.FakeFileSystem
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Failure containment for copy: the source graph switching under the picker (closed driver) and
 * faults inside [PageMergeService]'s owned scope. Extends the `UpgradeResilienceTest` pattern
 * (which already covers the raw `PageRepository` reads) to [ActiveDbPageSource] and the service.
 */
class MergeResilienceTest {
    private val src = GraphId("aaaaaaaaaaaaaaaa")
    private val dst = GraphId("bbbbbbbbbbbbbbbb")
    private val epoch = Instant.fromEpochMilliseconds(0)

    private class UncaughtRecorder : AutoCloseable {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        private val previous = Thread.getDefaultUncaughtExceptionHandler()

        init {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        }

        override fun close() = Thread.setDefaultUncaughtExceptionHandler(previous)
    }

    private fun hex(prefix: Int, n: Int) = "00000000-0000-0000-%04x-%012x".format(prefix, n)

    // ── closed driver under the picker ──────────────────────────────────────────────────

    private class Db {
        val driver: SqlDriver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        val pages = SqlDelightPageRepository(database)
        val blocks = SqlDelightBlockRepository(database)
        val search = SqlDelightSearchRepository(database)
        val source = ActiveDbPageSource(pages, blocks, search)
    }

    private suspend fun Db.seed(count: Int) {
        val rows = (1..count).map { Page(uuid = PageUuid(hex(1, it)), name = "work/page $it", createdAt = epoch, updatedAt = epoch) }
        assertTrue(pages.savePages(rows).isRight())
        val first = rows.first()
        val b = Block(BlockUuid(hex(2, 1)), first.uuid, content = "one", position = "a0", createdAt = epoch, updatedAt = epoch)
        assertTrue(blocks.saveBlocks(listOf(b)).isRight())
    }

    private fun assertReadFailed(label: String, result: Either<DomainError, *>) {
        assertIs<DomainError.DatabaseError.ReadFailed>(result.leftOrNull(), "$label must be Left(ReadFailed), was $result")
    }

    // Title search reports a closed driver as WriteFailed (existing SqlDelightSearchRepository labelling); still a Left, never a throw.
    private fun assertDbLeft(label: String, result: Either<DomainError, *>) {
        assertIs<DomainError.DatabaseError>(result.leftOrNull(), "$label must be a Left(DatabaseError), was $result")
    }

    @Test
    fun `source switching under the picker yields Left ReadFailed instead of a crash`() = runBlocking {
        UncaughtRecorder().use { recorder ->
            val db = Db().also { it.seed(250) }
            val filter = SelectionFilter(journals = false, namespace = "work/")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val live = db.source.listPages(filter, null, 100, 0)
                assertEquals(100, live.getOrNull()!!.size)

                // A standing picker collector, as a LaunchedEffect would hold.
                val seen = CopyOnWriteArrayList<Either<DomainError, List<Page>>>()
                val collector = scope.launch { db.pages.getPagesFiltered(filter, 100, 0).collect { seen += it } }
                withTimeout(10_000) { while (seen.isEmpty()) kotlinx.coroutines.delay(10) }

                db.driver.close() // GraphManager.switchGraph / shutdown

                assertReadFailed("listPages", db.source.listPages(filter, null, 100, 0))
                assertReadFailed("countPages", db.source.countPages(filter, null))
                assertDbLeft("listPages(search)", db.source.listPages(filter, "page", 100, 0))
                assertDbLeft("countPages(search)", db.source.countPages(filter, "page"))
                assertReadFailed("readPages", db.source.readPages(listOf(PageUuid(hex(1, 1)))))
                assertReadFailed("getPagesFiltered", db.pages.getPagesFiltered(filter, 10, 0).first())
                assertReadFailed("getPageByUuid", db.pages.getPageByUuid(PageUuid(hex(1, 200))).first())
                assertReadFailed("getPageByName", db.pages.getPageByName("work/page 200").first())
                assertReadFailed("countPagesFiltered", db.pages.countPagesFiltered(filter))
                assertReadFailed("getPagesAmong", db.pages.getPagesAmong(filter, listOf(PageUuid(hex(1, 1)))))
                collector.cancel()
            } finally {
                scope.cancel()
            }
            assertTrue(recorder.uncaught.isEmpty(), "uncaught: ${recorder.uncaught}")
        }
    }

    // ── faults inside PageMergeService's owned scope ────────────────────────────────────

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private class FakeSource(count: Int) : PageSource {
        private val epoch = Instant.fromEpochMilliseconds(0)
        private fun hex(prefix: Int, n: Int) = "00000000-0000-0000-%04x-%012x".format(prefix, n)
        val entries = (1..count).map { n ->
            val page = Page(uuid = PageUuid(hex(1, n)), name = "p%03d".format(n), createdAt = epoch, updatedAt = epoch)
            val u = hex(2, n)
            page to listOf(
                Block(BlockUuid(u), page.uuid, content = "body $n", position = "a0", createdAt = epoch, updatedAt = epoch, properties = mapOf("id" to u)),
            )
        }
        override suspend fun listPages(filter: SelectionFilter, search: String?, limit: Int, offset: Int) =
            entries.drop(offset).take(limit).map { it.first }.right()
        override suspend fun countPages(filter: SelectionFilter, search: String?) = entries.size.toLong().right()
        override suspend fun readPages(uuids: List<PageUuid>) =
            uuids.mapNotNull { id -> entries.firstOrNull { it.first.uuid == id } }.map { SourcePage(it.first, it.second) }.right()
    }

    private class FaultyTarget(var fault: (() -> Nothing)? = null) : TargetWriter {
        val pages = HashMap<String, MergePage>()
        override suspend fun readExisting(page: PageKey): Either<DomainError, MergePage?> = pages[page.name].right()
        override suspend fun write(page: PageKey, merged: MergePage): Either<DomainError, WriteOutcome> {
            fault?.invoke()
            pages[page.name] = merged
            return WriteOutcome.Created("pages/${page.name}.md", "h").right()
        }
        override suspend fun deletePageFile(page: PageKey, expectedHash: String): Either<DomainError, Unit> = Unit.right()
        override suspend fun fileHash(page: PageKey): Either<DomainError, String?> = null.right()
        override suspend fun removeBlocks(page: PageKey, uuids: Set<String>, expectedContentHashes: Map<String, String>) =
            RemoveReport(emptySet(), emptySet(), emptySet()).right()
    }

    private val managers = mutableListOf<GraphManager>()
    private val services = mutableListOf<PageMergeService>()

    @AfterTest
    fun cleanup() {
        services.forEach { it.close() }
        managers.forEach { it.shutdown() }
    }

    private class Env(val service: PageMergeService, val manager: GraphManager, val target: FaultyTarget)

    private fun env(): Env {
        val graphs = listOf(src, dst).map { GraphInfo(id = it, path = "/data/${it.value}", displayName = it.value, addedAt = 0L) }
        val settings = MapSettings()
        settings.putString("graph_registry", Json.encodeToString(GraphRegistry(activeGraphId = src, graphs = graphs)))
        val manager = GraphManager(settings, DriverFactory(), StubFileSystem(), GraphBackend.IN_MEMORY).also { managers += it }
        val target = FaultyTarget()
        val router = TargetWriterRouter(
            graphManager = manager,
            locator = RegistryGraphLocator(manager.graphRegistry),
            capabilities = TargetWriterCapabilities(platformSupportsOffGraphWrite = true),
            activeWriterFor = { target },
            offGraphWriterFor = { target },
        )
        val service = PageMergeService(router, FakeFileSystem(), "/app", nowEpochMs = { 1_000L }).also { services += it }
        return Env(service, manager, target)
    }

    private fun request() = PlanRequest(PageSelection(), src, dst, "A", LinkClosurePolicy.Off)

    private suspend fun Env.plan(source: PageSource): MergePlan {
        manager.awaitPendingMigration()
        return (service.plan(request(), source) as Either.Right).value
    }

    @Test
    fun `an exception thrown by the target becomes a failed page, the run completes`() = runBlocking {
        UncaughtRecorder().use { recorder ->
            val e = env()
            val plan = e.plan(FakeSource(5))
            e.target.fault = { throw IllegalStateException("disk exploded") }

            val result = (e.service.apply(plan) as Either.Right).value

            assertEquals(5, result.failed.size)
            assertEquals(MergePhase.Finished, e.service.progress.value.phase)
            assertTrue(recorder.uncaught.isEmpty(), "uncaught: ${recorder.uncaught}")
        }
    }

    @Test
    fun `a fatal Error thrown by the target is surfaced as a failure state, not an uncaught crash`() = runBlocking {
        UncaughtRecorder().use { recorder ->
            val e = env()
            val plan = e.plan(FakeSource(5))
            e.target.fault = { throw OutOfMemoryError("simulated heap exhaustion") }

            var threw: Throwable? = null
            val outcome = try {
                e.service.apply(plan)
            } catch (t: Throwable) {
                threw = t
                null
            }
            assertEquals(null, threw, "apply() must report a failure state, but threw ${threw?.let { it::class.simpleName }} to its caller")
            val phase = e.service.progress.value.phase

            assertTrue(outcome!!.isLeft() || (outcome as Either.Right).value.failed.isNotEmpty(), "outcome: $outcome")
            assertTrue(phase != MergePhase.Applying, "progress is stuck in $phase after the fault")
            assertTrue(recorder.uncaught.isEmpty(), "uncaught: ${recorder.uncaught}")
            // The service stays usable: a retry after the fault is not Busy.
            e.target.fault = null
            assertTrue(e.service.apply(plan) != ApplyFailure.Busy.left())
        }
    }

    @Test
    fun `a source that throws while staging is a failure state, not an uncaught crash`() = runBlocking {
        UncaughtRecorder().use { recorder ->
            val e = env()
            val good = FakeSource(5)
            var broken = false
            val flaky = object : PageSource by good {
                override suspend fun readPages(uuids: List<PageUuid>): Either<DomainError, List<SourcePage>> {
                    if (broken) throw IllegalStateException("driver closed mid-copy")
                    return good.readPages(uuids)
                }
            }
            val plan = e.plan(flaky)
            broken = true

            var threw: Throwable? = null
            val outcome = try {
                e.service.apply(plan)
            } catch (t: Throwable) {
                threw = t
                null
            }
            assertEquals(null, threw, "apply() must report a failure state, but threw ${threw?.let { it::class.simpleName }} to its caller")

            assertTrue(outcome!!.isLeft(), "outcome: $outcome")
            assertTrue(e.service.progress.value.phase != MergePhase.Applying, "progress stuck: ${e.service.progress.value}")
            assertTrue(recorder.uncaught.isEmpty(), "uncaught: ${recorder.uncaught}")
        }
    }
}
