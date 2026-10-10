@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.repository.SearchRepository
import dev.stapler.stelekit.repository.SqlDelightBlockRepository
import dev.stapler.stelekit.repository.SqlDelightPageRepository
import dev.stapler.stelekit.repository.SqlDelightSearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ActiveDbPageSourceTest {
    private val t = Instant.fromEpochMilliseconds(0)
    private val ids = HashMap<String, Int>()

    // Page validation requires UUID-shaped ids; one stable id per name.
    private fun uuidFor(name: String): String =
        "00000000-0000-0000-0000-" + ids.getOrPut(name) { ids.size + 1 }.toString(16).padStart(12, '0')

    private fun page(
        name: String,
        journal: LocalDate? = null,
        props: Map<String, String> = emptyMap(),
    ) = Page(
        uuid = PageUuid(uuidFor(name)),
        name = name,
        createdAt = t,
        updatedAt = t,
        properties = props,
        isJournal = journal != null,
        journalDate = journal,
    )

    private class Db {
        val database = SteleDatabase(DriverFactory().createDriver("jdbc:sqlite::memory:"))
        val pages = SqlDelightPageRepository(database)
        val blocks = SqlDelightBlockRepository(database)
        val search = SqlDelightSearchRepository(database)
    }

    private suspend fun SqlDelightPageRepository.seed(all: List<Page>) {
        all.chunked(500).forEach { assertTrue(savePages(it).isRight()) }
    }

    private suspend fun PageRepository.listAll(filter: SelectionFilter, pageSize: Int): List<Page> {
        val out = mutableListOf<Page>()
        while (true) {
            val batch = getPagesFiltered(filter, pageSize, out.size).first().getOrNullOrFail()
            out += batch
            if (batch.size < pageSize) return out
        }
    }

    private fun <A, B> Either<A, B>.getOrNullOrFail(): B = fold({ error("unexpected Left: $it") }, { it })

    // ── SQL agrees with the SelectionFilter oracle ──────────────────────────────────────

    private val oracleCorpus = listOf(
        page("Work/Roadmap", props = mapOf("tags" to "road")),
        page("work/b", props = mapOf("tags" to "#Road")),
        page("work_x"),
        page("work%y", props = mapOf("tag" to "road")),
        page("WORKS"),
        page("[bracket"),
        page("@at", props = mapOf("tags" to "roadmap")),
        page("Zeta"),
        page("alpha"),
        page("2024-01-15", LocalDate(2024, 1, 15)),
        page("2024-02-10", LocalDate(2024, 2, 10), props = mapOf("tags" to "road")),
        page("2024-03-01", LocalDate(2024, 3, 1)),
        page("100%_done", props = mapOf("tags" to "50%_x")),
    )

    private val oracleFilters = listOf(
        SelectionFilter(),
        SelectionFilter(journals = false),
        SelectionFilter(namespace = "work/"),
        SelectionFilter(namespace = "WORK"),
        SelectionFilter(namespace = "work_"),
        SelectionFilter(namespace = "work%"),
        SelectionFilter(namespace = "@"),
        SelectionFilter(namespace = "["),
        SelectionFilter(journals = false, namespace = "work/"),
        SelectionFilter(dateFrom = LocalDate(2024, 2, 1)),
        SelectionFilter(dateFrom = LocalDate(2024, 1, 15), dateTo = LocalDate(2024, 2, 10)),
        SelectionFilter(dateTo = LocalDate(2024, 1, 31)),
        SelectionFilter(journals = false, dateFrom = LocalDate(2024, 1, 1)),
        SelectionFilter(tag = "road"),
        SelectionFilter(tag = "#ROAD", namespace = "work"),
        SelectionFilter(tag = "road", dateFrom = LocalDate(2024, 1, 1)),
        SelectionFilter(tag = "50%_x"),
        SelectionFilter(tag = "nope"),
    )

    @Test
    fun `sql repository matches the predicate oracle for every filter`() = runBlocking {
        val db = Db()
        db.pages.seed(oracleCorpus)
        val stored = db.pages.getAllPagesSnapshot().getOrNullOrFail()
        for (filter in oracleFilters) {
            val expected = stored.filteredAndSorted(filter).map { it.name }
            assertEquals(expected, db.pages.listAll(filter, pageSize = 3).map { it.name }, "list $filter")
            assertEquals(expected.size.toLong(), db.pages.countPagesFiltered(filter).getOrNullOrFail(), "count $filter")
            val among = db.pages.getPagesAmong(filter, stored.map { it.uuid }).getOrNullOrFail()
            assertEquals(expected.toSet(), among.map { it.name }.toSet(), "among $filter")
        }
    }

    @Test
    fun `in-memory repository matches the predicate oracle for every filter`() = runBlocking {
        val repo = InMemoryPageRepository()
        repo.savePages(oracleCorpus)
        for (filter in oracleFilters) {
            val expected = oracleCorpus.filteredAndSorted(filter).map { it.name }
            assertEquals(expected, repo.listAll(filter, pageSize = 3).map { it.name }, "list $filter")
            assertEquals(expected.size.toLong(), repo.countPagesFiltered(filter).getOrNullOrFail(), "count $filter")
        }
    }

    // ── ActiveDbPageSource behavior ─────────────────────────────────────────────────────

    @Test
    fun `search results are the title hits that pass the filter`() = runBlocking {
        val db = Db()
        db.pages.seed(
            listOf(
                page("work/road trip"), page("work/roadmap"), page("misc/road atlas"),
                page("work/other"), page("2024-01-01 road", LocalDate(2024, 1, 1)),
            )
        )
        val source = ActiveDbPageSource(db.pages, db.blocks, db.search)
        val filter = SelectionFilter(journals = false, namespace = "work/")

        val names = source.listPages(filter, "road", 50, 0).getOrNullOrFail().map { it.name }.toSet()
        assertEquals(setOf("work/road trip", "work/roadmap"), names)
        assertEquals(2L, source.countPages(filter, "road").getOrNullOrFail())
        assertEquals(1, source.listPages(filter, "road", 50, 1).getOrNullOrFail().size)
    }

    @Test
    fun `search finds filter-passing hits beyond the first 100 title matches`() = runBlocking {
        val db = Db()
        db.pages.seed(
            (1..130).map { page("misc/road $it") } + (1..30).map { page("work/road $it") }
        )
        val pages = ActiveDbPageSource(db.pages, db.blocks, db.search)
        val filter = SelectionFilter(journals = false, namespace = "work/")
        assertEquals(30L, pages.countPages(filter, "road").getOrNullOrFail())
        val all = pages.listPages(filter, "road", 100, 0).getOrNullOrFail()
        assertEquals(30, all.size)
        assertTrue(all.all { it.name.startsWith("work/road") })
        assertEquals(10, pages.listPages(filter, "road", 100, 20).getOrNullOrFail().size)
    }

    @Test
    fun `readPages returns each page with its blocks`() = runBlocking {
        val db = Db()
        val p = page("work/a")
        db.pages.seed(listOf(p, page("work/b")))
        db.blocks.saveBlocks(
            listOf(
                Block(BlockUuid("00000000-0000-0000-0001-000000000001"), p.uuid, content = "one", position = "a0", createdAt = t, updatedAt = t),
                Block(BlockUuid("00000000-0000-0000-0001-000000000002"), p.uuid, content = "two", position = "a1", createdAt = t, updatedAt = t),
            )
        )
        val source = ActiveDbPageSource(db.pages, db.blocks, db.search)
        val read = source.readPages(listOf(p.uuid, PageUuid("missing"))).getOrNullOrFail()
        assertEquals(1, read.size)
        assertEquals(listOf("one", "two"), read.single().toMergePage().blocks.map { it.content })
    }

    // ── 8 030 pages: bounded, O(1) queries per filter/search change ─────────────────────

    private class RecordingPages(private val d: PageRepository) : PageRepository by d {
        val calls = CopyOnWriteArrayList<String>()
        val maxRows = java.util.concurrent.atomic.AtomicInteger(0)
        val maxIds = java.util.concurrent.atomic.AtomicInteger(0)

        private fun saw(name: String, rows: Int) {
            calls += name
            maxRows.updateAndGet { maxOf(it, rows) }
        }

        override fun getPagesFiltered(filter: SelectionFilter, limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> =
            d.getPagesFiltered(filter, limit, offset).onEach { saw("getPagesFiltered", it.getOrNullOrFailStatic().size) }

        override suspend fun countPagesFiltered(filter: SelectionFilter): Either<DomainError, Long> {
            saw("countPagesFiltered", 1)
            return d.countPagesFiltered(filter)
        }

        override suspend fun getPagesAmong(filter: SelectionFilter, uuids: Collection<PageUuid>): Either<DomainError, List<Page>> {
            maxIds.updateAndGet { maxOf(it, uuids.size) }
            return d.getPagesAmong(filter, uuids).also { saw("getPagesAmong", it.getOrNullOrFailStatic().size) }
        }

        // Any whole-graph or paginated-scan read is a regression for the picker.
        override fun getPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> {
            calls += "getPages"
            return d.getPages(limit, offset)
        }

        override suspend fun getAllPagesSnapshot(batchSize: Int): Either<DomainError, List<Page>> {
            calls += "getAllPagesSnapshot"
            return d.getAllPagesSnapshot(batchSize)
        }

        private fun <A, B> Either<A, B>.getOrNullOrFailStatic(): B = fold({ error("unexpected Left: $it") }, { it })
    }

    private class RecordingSearch(private val d: SearchRepository) : SearchRepository by d {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val maxLimit = java.util.concurrent.atomic.AtomicInteger(0)
        val maxRows = java.util.concurrent.atomic.AtomicInteger(0)

        override fun searchPagesByTitle(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> {
            calls.incrementAndGet()
            maxLimit.updateAndGet { maxOf(it, limit) }
            return d.searchPagesByTitle(query, limit, offset).onEach { r ->
                maxRows.updateAndGet { maxOf(it, r.fold({ 0 }, { p -> p.size })) }
            }
        }
    }

    @Test
    fun `filter and search changes on 8030 pages issue O(1) bounded queries`() = runBlocking {
        val db = Db()
        val corpus = buildList {
            for (i in 1..4_000) add(page("work/page $i"))
            for (i in 1..150) add(page("work/road $i"))
            for (i in 1..3_850) add(page("misc/page $i"))
            for (i in 1..30) add(page("2026-06-${i.toString().padStart(2, '0')}", LocalDate(2026, 6, i)))
        }
        assertEquals(8_030, corpus.size)
        db.pages.seed(corpus)

        val pages = RecordingPages(db.pages)
        val search = RecordingSearch(db.search)
        val source = ActiveDbPageSource(pages, db.blocks, search)

        // The picker's three UI reactions: filter change, search change, both together.
        val filter = SelectionFilter(journals = false, namespace = "work/")
        val listed = source.listPages(filter, null, 100, 0).getOrNullOrFail()
        val total = source.countPages(filter, null).getOrNullOrFail()
        assertEquals(100, listed.size)
        assertEquals(4_150L, total)
        assertEquals(listOf("getPagesFiltered", "countPagesFiltered"), pages.calls.toList())

        // "road" has 150 matches: more than one hit page, so counts/lists must page past 100.
        pages.calls.clear()
        val hits = source.listPages(filter, "road", 100, 0).getOrNullOrFail()
        val tail = source.listPages(filter, "road", 100, 100).getOrNullOrFail()
        val hitCount = source.countPages(filter, "road").getOrNullOrFail()
        assertTrue(hits.all { it.name.startsWith("work/road") } && tail.all { it.name.startsWith("work/road") })
        assertEquals(100, hits.size)
        assertEquals(50, tail.size)
        assertEquals(150L, hitCount)
        assertEquals(150, (hits + tail).map { it.uuid }.toSet().size)
        assertTrue(pages.calls.all { it == "getPagesAmong" } && pages.calls.size <= 6, "bounded: ${pages.calls}")

        assertTrue(pages.maxRows.get() <= 100, "repository call returned ${pages.maxRows.get()} rows")
        assertTrue(pages.maxIds.get() <= 100, "intersect chunk was ${pages.maxIds.get()} ids")
        assertTrue(search.maxLimit.get() <= 100 && search.maxRows.get() <= 100)
        assertTrue("getPages" !in pages.calls && "getAllPagesSnapshot" !in pages.calls)
    }
}
