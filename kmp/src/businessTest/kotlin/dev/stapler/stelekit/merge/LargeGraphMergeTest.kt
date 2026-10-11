@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.FakeRelocationFileSystem
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
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.repository.SqlDelightBlockRepository
import dev.stapler.stelekit.repository.SqlDelightPageRepository
import dev.stapler.stelekit.repository.SqlDelightSearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Source
import okio.fakefilesystem.FakeFileSystem
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The 8 030-page SLO for copy (same page count as `LargeGraphWarmStartCrashTest`): a full copy
 * through the real [PageMergeService] and [TargetWriterRouter] must finish with no uncaught
 * Throwable, only bounded repository reads, and a second run that is all `Unchanged`.
 */
class LargeGraphMergeTest {
    private companion object {
        const val PAGE_COUNT = 8_030
        const val JOURNAL_COUNT = 10
        const val GHOST_LINKS = 1_200
        const val TIMEOUT_MS = 600_000L
    }

    private val src = GraphId("aaaaaaaaaaaaaaaa")
    private val dst = GraphId("bbbbbbbbbbbbbbbb")
    private val epoch = Instant.fromEpochMilliseconds(0)

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private class UncaughtRecorder : AutoCloseable {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        private val previous = Thread.getDefaultUncaughtExceptionHandler()

        init {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        }

        override fun close() = Thread.setDefaultUncaughtExceptionHandler(previous)
    }

    /** Largest result of any bounded read; the unbounded reads must never be called. */
    private class RecordingPages(private val d: PageRepository) : PageRepository by d {
        val maxRows = AtomicInteger(0)
        val maxNames = AtomicInteger(0)
        val forbidden = CopyOnWriteArrayList<String>()

        private fun saw(rows: Int) { maxRows.updateAndGet { maxOf(it, rows) } }

        override fun getPagesFiltered(filter: SelectionFilter, limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> {
            maxRows.updateAndGet { maxOf(it, limit) }
            return d.getPagesFiltered(filter, limit, offset).onEach { r -> saw(r.fold({ 0 }, { it.size })) }
        }

        override suspend fun getPagesByNames(names: Collection<String>): Either<DomainError, List<Page>> {
            maxNames.updateAndGet { maxOf(it, names.size) }
            return d.getPagesByNames(names).also { r -> saw(r.fold({ 0 }, { it.size })) }
        }

        override fun getPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> {
            forbidden += "getPages"
            return d.getPages(limit, offset)
        }

        override suspend fun getAllPagesSnapshot(batchSize: Int): Either<DomainError, List<Page>> {
            forbidden += "getAllPagesSnapshot"
            return d.getAllPagesSnapshot(batchSize)
        }
    }

    private class RecordingBlocks(private val d: BlockRepository) : BlockRepository by d {
        val maxRows = AtomicInteger(0)

        override fun getBlocksForPage(pageUuid: PageUuid): Flow<Either<DomainError, List<Block>>> =
            d.getBlocksForPage(pageUuid).onEach { r -> maxRows.updateAndGet { maxOf(it, r.fold({ 0 }, { b -> b.size })) } }
    }

    /**
     * Events on one clock: a staged page read (+1) or any target access (reset). The longest run of
     * staged reads with no target access between them is the most pages the service held at once.
     */
    private class Activity {
        private var run = 0
        var peakStagedRun = 0
        var stagedReads = 0
        var targetWrites = 0

        @Synchronized fun stagedRead() { run++; stagedReads++; if (run > peakStagedRun) peakStagedRun = run }
        @Synchronized fun targetAccess() { run = 0 }
        @Synchronized fun targetWrite() { run = 0; targetWrites++ }
    }

    private class CountingStagingFs(delegate: FileSystem, private val activity: Activity) : ForwardingFileSystem(delegate) {
        override fun source(file: Path): Source {
            if (file.parent?.name?.startsWith(MergeStagingDirectory.STAGING_PREFIX) == true && file.name != ".marker") activity.stagedRead()
            return super.source(file)
        }
    }

    private class CountingTarget(private val d: TargetWriter, private val activity: Activity) : TargetWriter by d {
        override suspend fun readExisting(page: PageKey): Either<DomainError, MergePage?> {
            activity.targetAccess()
            return d.readExisting(page)
        }

        override suspend fun write(page: PageKey, merged: MergePage): Either<DomainError, WriteOutcome> {
            activity.targetWrite()
            return d.write(page, merged)
        }
    }

    private val managers = mutableListOf<GraphManager>()
    private val services = mutableListOf<PageMergeService>()

    @AfterTest
    fun cleanup() {
        services.forEach { it.close() }
        managers.forEach { it.shutdown() }
    }

    private fun manager(active: GraphId): GraphManager {
        val graphs = listOf(src, dst).map { GraphInfo(id = it, path = "/data/${it.value}", displayName = it.value, addedAt = 0L) }
        val settings = MapSettings()
        settings.putString("graph_registry", Json.encodeToString(GraphRegistry(activeGraphId = active, graphs = graphs)))
        return GraphManager(
            platformSettings = settings,
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        ).also { managers += it }
    }

    private fun hex(prefix: Int, n: Int) = "00000000-0000-0000-%04x-%012x".format(prefix, n)

    /** [PAGE_COUNT] pages (incl. journals) in an in-memory SQLDelight DB; the first [GHOST_LINKS] link to a distinct missing page. */
    private suspend fun seedSource(): Seeded {
        val db = SteleDatabase(DriverFactory().createDriver("jdbc:sqlite::memory:"))
        val pageRepo = SqlDelightPageRepository(db)
        val blockRepo = SqlDelightBlockRepository(db)
        val ordinary = PAGE_COUNT - JOURNAL_COUNT
        val pages = ArrayList<Page>(PAGE_COUNT)
        val blocks = ArrayList<Block>(PAGE_COUNT * 3)
        for (n in 1..ordinary) {
            val page = Page(uuid = PageUuid(hex(1, n)), name = "Large Page %05d".format(n), createdAt = epoch, updatedAt = epoch)
            pages += page
            val texts = buildList {
                add("first block of $n")
                add("second block with [[Large Page 00001]] link")
                if (n <= GHOST_LINKS) add("ghost link [[Ghost $n]]")
            }
            texts.forEachIndexed { i, text ->
                val u = hex(2, n * 10 + i)
                blocks += Block(
                    uuid = BlockUuid(u), pageUuid = page.uuid, content = text, position = "a$i",
                    createdAt = epoch, updatedAt = epoch, properties = mapOf("id" to u),
                )
            }
        }
        for (d in 1..JOURNAL_COUNT) {
            val page = Page(
                uuid = PageUuid(hex(3, d)), name = "2026-06-%02d".format(d), createdAt = epoch, updatedAt = epoch,
                isJournal = true, journalDate = LocalDate(2026, 6, d),
            )
            pages += page
            val u = hex(4, d)
            blocks += Block(
                uuid = BlockUuid(u), pageUuid = page.uuid, content = "journal entry $d", position = "a0",
                createdAt = epoch, updatedAt = epoch, properties = mapOf("id" to u),
            )
        }
        assertEquals(PAGE_COUNT, pages.size)
        pages.chunked(500).forEach { assertTrue(pageRepo.savePages(it).isRight()) }
        blocks.chunked(500).forEach { assertTrue(blockRepo.saveBlocks(it).isRight()) }
        val recPages = RecordingPages(pageRepo)
        val recBlocks = RecordingBlocks(blockRepo)
        return Seeded(recPages, recBlocks, ActiveDbPageSource(recPages, recBlocks, SqlDelightSearchRepository(db)))
    }

    private class Seeded(val pages: RecordingPages, val blocks: RecordingBlocks, val source: ActiveDbPageSource)

    private class Run(val result: MergeResult, val planSummary: DryRunSummary, val millis: Long)

    private suspend fun fullCopy(
        service: PageMergeService,
        source: PageSource,
        manager: GraphManager,
    ): Run {
        val started = System.nanoTime()
        manager.awaitPendingMigration()
        val request = PlanRequest(
            selection = PageSelection(), sourceGraphId = src, targetGraphId = dst, sourceGraphName = "A",
            closure = LinkClosurePolicy.Depth1(),
        )
        val plan = (service.plan(request, source) as Either.Right).value
        val result = (service.apply(plan) as Either.Right).value
        return Run(result, plan.summary, (System.nanoTime() - started) / 1_000_000)
    }

    private fun assertBounded(pages: RecordingPages, blocks: RecordingBlocks) {
        assertEquals(emptyList(), pages.forbidden.toList(), "unbounded page reads")
        assertTrue(pages.maxRows.get() in 1..100, "page read returned ${pages.maxRows.get()} rows")
        assertTrue(pages.maxNames.get() in 1..500, "IN lookup had ${pages.maxNames.get()} names")
        assertTrue(blocks.maxRows.get() in 1..100, "block read returned ${blocks.maxRows.get()} rows")
    }

    @Test
    fun `8030-page copy to an off-graph markdown target is bounded and idempotent`() = runBlocking {
        UncaughtRecorder().use { recorder ->
            withTimeout(TIMEOUT_MS) {
                val seeded = seedSource()
                val pages = seeded.pages
                val source = seeded.source
                val activity = Activity()
                val staging = CountingStagingFs(FakeFileSystem(), activity)
                val targetFs = FakeRelocationFileSystem()
                val mgr = manager(active = src)
                val writer = CountingTarget(
                    MarkdownTargetWriter(
                        targetFs, OffGraphTarget(dst, "/data/${dst.value}", isActive = false),
                        TargetWriterCapabilities(platformSupportsOffGraphWrite = true), MarkdownTargetWriter.NoSymlinks,
                    ),
                    activity,
                )
                val router = TargetWriterRouter(
                    graphManager = mgr,
                    locator = RegistryGraphLocator(mgr.graphRegistry),
                    capabilities = TargetWriterCapabilities(platformSupportsOffGraphWrite = true),
                    activeWriterFor = { writer },
                    offGraphWriterFor = { writer },
                )
                val service = PageMergeService(
                    router, staging, "/app", closureLookup = pages::getPagesByNames,
                    graphRoot = { "/data/${it.value}" },
                ).also { services += it }

                val first = fullCopy(service, source, mgr)
                println("LargeGraphMergeTest markdown run1 ms=${first.millis} ${first.result}")
                assertEquals(0, first.result.failed.size, "failed: ${first.result.failed.take(3)}")
                assertEquals(PAGE_COUNT, first.result.total)
                assertEquals(PAGE_COUNT, first.result.newPages)
                assertEquals(PAGE_COUNT, activity.targetWrites)

                val second = fullCopy(service, source, mgr)
                println("LargeGraphMergeTest markdown run2 ms=${second.millis} ${second.result}")
                assertEquals(PAGE_COUNT, second.planSummary.unchanged, "second plan: ${second.planSummary}")
                assertEquals(PAGE_COUNT, second.result.unchangedPages, "second apply: ${second.result}")
                assertEquals(0, second.result.newPages + second.result.combinedPages + second.result.failed.size)
                assertEquals(PAGE_COUNT, activity.targetWrites, "second run must not write")

                assertBounded(pages, seeded.blocks)
                println("LargeGraphMergeTest peakStagedRun=${activity.peakStagedRun} (service chunk bound ${PageSource.MAX_PAGE_SIZE})")
                assertTrue(activity.peakStagedRun in 1..PageSource.MAX_PAGE_SIZE, "staged pages held at once: ${activity.peakStagedRun}")
                assertTrue(recorder.uncaught.isEmpty(), "uncaught: ${recorder.uncaught.map { it::class.simpleName + ": " + it.message }}")
            }
        }
    }

    @Test
    fun `staging directory holds at most one decoded page while iterating`() = runBlocking {
        val activity = Activity()
        val fs = CountingStagingFs(FakeFileSystem(), activity)
        val staging = MergeStagingDirectory.create(fs, "/app", MergeId("m1"), src, dst, 0L).getOrNull()!!
        val total = 500
        repeat(total) { i ->
            val page = MergePage("P$i", blocks = listOf(MergeBlock(hex(5, i), "body $i")))
            assertTrue(staging.writePage(i, page).isRight())
        }
        var consumed = 0
        var maxAhead = 0
        for (page in staging.readAll()) {
            assertTrue(page.isRight())
            consumed++
            maxAhead = maxOf(maxAhead, activity.stagedReads - consumed)
        }
        assertEquals(total, consumed)
        assertEquals(0, maxAhead, "readAll must decode lazily, one page ahead of the consumer at most")
    }
}
