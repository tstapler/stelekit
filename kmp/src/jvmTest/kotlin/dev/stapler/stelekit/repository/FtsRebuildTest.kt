package dev.stapler.stelekit.repository

import dev.stapler.stelekit.benchmark.SyntheticGraphDbBuilder
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
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
        ).first()
        assertTrue(searchResult.isRight(), "Search after FTS rebuild should succeed")
        val ranked = searchResult.getOrNull()?.ranked.orEmpty()
        assertTrue(ranked.isNotEmpty(), "Should find results after FTS rebuild")
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
