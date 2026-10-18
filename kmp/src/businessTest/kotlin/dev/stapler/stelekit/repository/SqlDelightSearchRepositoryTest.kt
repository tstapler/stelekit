@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.merge.SelectionFilter
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/** [SqlDelightSearchRepository] against a real in-memory SQLite database with live FTS5 tables. */
class SqlDelightSearchRepositoryTest {

    private class Fixture(val search: SqlDelightSearchRepository)

    private fun now() = Clock.System.now()

    private fun fixture(): Fixture {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val db = SteleDatabase(driver)
        val pages = SqlDelightPageRepository(db)
        val blocks = SqlDelightBlockRepository(db, driver)
        runBlocking {
            pages.savePage(Page(uuid = PageUuid("p-alpha"), name = "Alpha Project", createdAt = now(), updatedAt = now()))
            pages.savePage(Page(uuid = PageUuid("p-fav"), name = "Favorite Garden", isFavorite = true, createdAt = now(), updatedAt = now()))
            pages.savePage(Page(uuid = PageUuid("p-jr"), name = "2025-01-02", isJournal = true, createdAt = now(), updatedAt = now()))
            blocks.saveBlocks(listOf(
                block("b1", "p-alpha", "quarterly budget review meeting", "a0"),
                block("b2", "p-fav", "budget for the garden shed", "a0"),
                block("b3", "p-jr", "journal entry about budget", "a0"),
                block("b4", "p-alpha", "50% off C++ coupons & [brackets] \"quoted\" (parens)", "a1"),
            ))
        }
        return Fixture(SqlDelightSearchRepository(db, driver = driver))
    }

    private fun block(uuid: String, page: String, content: String, position: String) = Block(
        uuid = BlockUuid(uuid), pageUuid = PageUuid(page), content = content, level = 0, position = position,
        createdAt = now(), updatedAt = now(),
    )

    private suspend fun Fixture.run(req: SearchRequest): SearchResult =
        search.searchWithFilters(req).toList().last().getOrNull()!!  // final emission; an interim page-only one precedes it

    @Test
    fun `scope BLOCKS_ONLY returns blocks and no pages`() = runBlocking {
        val r = fixture().run(SearchRequest(query = "budget", scope = SearchScope.BLOCKS_ONLY))
        assertEquals(setOf("b1", "b2", "b3"), r.blocks.map { it.uuid.value }.toSet())
        assertTrue(r.pages.isEmpty())
    }

    @Test
    fun `scope PAGES_ONLY with title data type finds pages by name`() = runBlocking {
        val r = fixture().run(SearchRequest(query = "garden", scope = SearchScope.PAGES_ONLY, dataTypes = setOf(DataType.TITLES)))
        assertEquals(listOf("p-fav"), r.pages.map { it.uuid.value })
        assertTrue(r.blocks.isEmpty())
    }

    @Test
    fun `FAVORITES scope combined with a content query keeps only favourite pages`() = runBlocking {
        val r = fixture().run(SearchRequest(query = "garden", scope = SearchScope.FAVORITES))
        assertTrue(r.pages.isNotEmpty())
        assertTrue(r.pages.all { it.isFavorite })
    }

    @Test
    fun `JOURNAL scope keeps only journal pages`() = runBlocking {
        val r = fixture().run(SearchRequest(query = "2025", scope = SearchScope.JOURNAL, dataTypes = setOf(DataType.TITLES)))
        assertEquals(listOf("p-jr"), r.pages.map { it.uuid.value })
    }

    @Test
    fun `CURRENT_PAGE scope without a page uuid yields no pages`() = runBlocking {
        val r = fixture().run(SearchRequest(query = "alpha", scope = SearchScope.CURRENT_PAGE, dataTypes = setOf(DataType.TITLES)))
        assertTrue(r.pages.isEmpty())
    }

    @Test
    fun `date range excluding now returns nothing and a covering range returns hits`() = runBlocking {
        val f = fixture()
        val past = DateRange(endDate = kotlin.time.Instant.fromEpochMilliseconds(1_000))
        assertTrue(f.run(SearchRequest(query = "budget", dateRange = past)).blocks.isEmpty())
        val covering = DateRange(startDate = kotlin.time.Instant.fromEpochMilliseconds(0))
        assertTrue(f.run(SearchRequest(query = "budget", dateRange = covering)).blocks.isNotEmpty())
    }

    @Test
    fun `blank and operator-only queries return an empty result`() = runBlocking {
        val f = fixture()
        for (q in listOf(null, "", "   ", "AND OR NOT")) {
            val r = f.run(SearchRequest(query = q))
            assertTrue(r.blocks.isEmpty() && r.pages.isEmpty(), "query=$q")
            assertEquals(0, r.totalCount)
        }
    }

    @Test
    fun `FTS special characters never throw and still match literal words`() = runBlocking {
        val f = fixture()
        for (q in listOf("C++", "50%", "[brackets]", "\"quoted\"", "\"unclosed", "(parens)", "a:b", "^caret", "{x}", "NEAR(", "*")) {
            val result = f.search.searchWithFilters(SearchRequest(query = q)).toList().last()
            assertTrue(result.isRight(), "query '$q' failed: $result")
        }
        val r = f.run(SearchRequest(query = "\"quoted\"", scope = SearchScope.BLOCKS_ONLY))
        assertEquals(listOf("b4"), r.blocks.map { it.uuid.value })
    }

    @Test
    fun `multi-token AND query narrows and falls back to OR when nothing matches`() = runBlocking {
        val f = fixture()
        val and = f.run(SearchRequest(query = "budget garden", scope = SearchScope.BLOCKS_ONLY))
        assertEquals(listOf("b2"), and.blocks.map { it.uuid.value })
        val orFallback = f.run(SearchRequest(query = "garden coupons", scope = SearchScope.BLOCKS_ONLY))
        assertEquals(setOf("b2", "b4"), orFallback.blocks.map { it.uuid.value }.toSet())
    }

    @Test
    fun `rebuildFts and integrityCheckFts succeed and search still works after`() = runBlocking {
        val f = fixture()
        assertTrue(f.search.rebuildFts().isRight())
        assertTrue(f.search.integrityCheckFts().isRight())
        val r = f.run(SearchRequest(query = "budget", scope = SearchScope.BLOCKS_ONLY))
        assertEquals(3, r.blocks.size)
    }

    @Test
    fun `searchBlocksByContent searchPagesByTitle and countPagesByTitle agree with FTS`() = runBlocking {
        val f = fixture()
        assertEquals(3, f.search.searchBlocksByContent("budget", 10, 0).first().getOrNull()!!.size)
        assertEquals(listOf("p-alpha"), f.search.searchPagesByTitle("alpha", 10, 0).first().getOrNull()!!.map { it.uuid.value })
        assertEquals(1L, f.search.countPagesByTitle("alpha", SelectionFilter()).getOrNull())
    }

    @Test
    fun `recordPageVisit returns Right and the page stays searchable`() = runBlocking {
        val f = fixture()
        assertTrue(f.search.recordPageVisit(PageUuid("p-alpha")).isRight())
        val r = f.run(SearchRequest(query = "alpha", dataTypes = setOf(DataType.TITLES)))
        assertEquals("p-alpha", r.pages.first().uuid.value)
    }
}
