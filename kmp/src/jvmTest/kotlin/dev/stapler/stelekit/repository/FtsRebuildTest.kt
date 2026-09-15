package dev.stapler.stelekit.repository

import dev.stapler.stelekit.benchmark.SyntheticGraphDbBuilder
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for FTS rebuild and integrity check.
 * Requirement: AC5 — rebuildFts() runs without error on large graph.
 *
 * Note: rebuildFts() returns Right(Unit) when no SqlDriver is passed (in-memory test path).
 * A real rebuild requires the driver to be injected. These tests verify the no-driver fallback
 * and the post-rebuild search functionality via SqlDelightSearchRepository.
 */
class FtsRebuildTest {

    // ── TC-13: integrityCheckFts returns Right on healthy index (no driver) ──

    @Test
    fun integrityCheckFts_returnsRightWithNoDriver() = runBlocking {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val db = SteleDatabase(driver)
        SyntheticGraphDbBuilder.populate(db, pageCount = 1_000, blocksPerPage = 5)
        // No driver passed — integrityCheck returns Right(Unit) gracefully
        val repo = SqlDelightSearchRepository(db)

        val result = repo.integrityCheckFts()
        assertTrue(result.isRight(), "integrityCheckFts should return Right(Unit) when no driver injected")
    }

    // ── TC-12: rebuildFts returns Right and search still works afterward ─────

    @Test
    fun rebuildFts_returnsRightAndSearchWorks() = runBlocking {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val db = SteleDatabase(driver)
        SyntheticGraphDbBuilder.populate(db, pageCount = 1_000, blocksPerPage = 5)

        // Pass the real driver so rebuild actually runs
        val repo = SqlDelightSearchRepository(db, driver = driver)

        val rebuildResult = repo.rebuildFts()
        assertTrue(rebuildResult.isRight(), "rebuildFts() should return Right(Unit)")

        // Subsequent search should still work
        val searchResult = repo.searchWithFilters(
            SearchRequest(query = "programming", limit = 5)
        ).last()
        assertTrue(searchResult.isRight(), "Search after FTS rebuild should succeed")
        val ranked = searchResult.getOrNull()?.ranked.orEmpty()
        assertTrue(ranked.isNotEmpty(), "Should find results after FTS rebuild")
    }

    // ── TC-BACKLINK-01: backlink counts are accurate after rebuildFts ─────────────

    @Test
    fun rebuildFts_computesCorrectBacklinkCounts() = runBlocking {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val db = SteleDatabase(driver)
        val queries = db.steleDatabaseQueries

        // Insert two pages: "Alpha" and "Beta"
        queries.insertPage("uuid-alpha", "Alpha", null, null, 0L, 0L, null, 1L, null, null, null, 1L, "")
        queries.insertPage("uuid-beta", "Beta", null, null, 0L, 0L, null, 1L, null, null, null, 1L, "")
        // Two blocks reference Alpha, one references Beta
        queries.insertBlock("block-1", "uuid-alpha", null, null, "See [[Alpha]] for details", 0L, "a0", 0L, 0L, null, 1L, null, "paragraph")
        queries.insertBlock("block-2", "uuid-alpha", null, null, "[[Alpha]] and [[Beta]] are linked", 0L, "a1", 0L, 0L, null, 1L, null, "paragraph")
        queries.insertBlock("block-3", "uuid-beta", null, null, "No wikilinks here", 0L, "a0", 0L, 0L, null, 1L, null, "paragraph")

        val repo = SqlDelightSearchRepository(db, driver = driver)
        val result = repo.rebuildFts()
        assertTrue(result.isRight(), "rebuildFts() should return Right(Unit)")

        val alphaCounts = queries.selectBacklinkCountsForPages(listOf("uuid-alpha")).executeAsList()
        val betaCounts = queries.selectBacklinkCountsForPages(listOf("uuid-beta")).executeAsList()
        assertEquals(2L, alphaCounts.first { it.page_name == "Alpha" }.backlink_count,
            "Alpha should have 2 backlinks")
        assertEquals(1L, betaCounts.first { it.page_name == "Beta" }.backlink_count,
            "Beta should have 1 backlink")
    }

    // ── integrityCheckFts with real driver ────────────────────────────────────

    @Test
    fun integrityCheckFts_returnsRightWithRealDriver() = runBlocking {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val db = SteleDatabase(driver)
        SyntheticGraphDbBuilder.populate(db, pageCount = 500, blocksPerPage = 5)

        val repo = SqlDelightSearchRepository(db, driver = driver)
        val result = repo.integrityCheckFts()
        assertTrue(result.isRight(), "integrityCheckFts should return Right(Unit) on healthy index")
    }
}
