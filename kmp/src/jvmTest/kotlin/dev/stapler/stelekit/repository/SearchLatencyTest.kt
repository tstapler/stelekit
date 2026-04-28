package dev.stapler.stelekit.repository

import dev.stapler.stelekit.benchmark.SyntheticGraphDbBuilder
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Latency assertions for search at scale.
 * Requirement: AC6 — p99 < 200ms at 10k pages.
 *
 * These tests use a cold in-memory SQLite DB (no warm OS page cache),
 * which is more conservative than a real on-disk scenario with warm cache.
 * If CI consistently fails at 200ms, investigate the query plan first
 * before raising the threshold.
 */
class SearchLatencyTest {

    private val queryTerms = listOf(
        "programming", "notes", "project", "meeting", "tax",
        "philosophy", "economics", "history", "design", "learning"
    )

    // ── TC-15: cold-start FTS query < 500ms ──────────────────────────────────

    @Test
    fun coldStartFtsQuery_under500ms() = runBlocking {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val db = SteleDatabase(driver)
        SyntheticGraphDbBuilder.populate(db, pageCount = 10_000, blocksPerPage = 5)
        val repo = SqlDelightSearchRepository(db)

        val start = System.currentTimeMillis()
        val result = repo.searchWithFilters(SearchRequest(query = "programming", limit = 50)).first()
        val coldStartMs = System.currentTimeMillis() - start

        assertTrue(result.isRight(), "Cold-start search should succeed")
        assertTrue(
            coldStartMs < 500,
            "Cold-start FTS query took ${coldStartMs}ms, expected < 500ms"
        )
    }

    // ── TC-14: p99 < 200ms at 10k pages ─────────────────────────────────────

    @Test
    fun searchLatency_10kPages_p99Under200ms() = runBlocking {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val db = SteleDatabase(driver)
        SyntheticGraphDbBuilder.populate(db, pageCount = 10_000, blocksPerPage = 5)
        val repo = SqlDelightSearchRepository(db)

        // Warm up — excluded from measurement
        repeat(5) { i ->
            val r = repo.searchWithFilters(SearchRequest(query = queryTerms[i % queryTerms.size], limit = 50)).first()
            assertTrue(r.isRight(), "Warm-up query $i should succeed")
        }

        // Measure 100 queries
        val latencies = mutableListOf<Long>()
        repeat(100) { i ->
            val query = queryTerms[i % queryTerms.size]
            val start = System.currentTimeMillis()
            val r = repo.searchWithFilters(SearchRequest(query = query, limit = 50)).first()
            latencies.add(System.currentTimeMillis() - start)
            assertTrue(r.isRight(), "Measured query $i should succeed")
        }

        latencies.sort()
        // Nearest-rank p99: ceil(0.99 * n) - 1 (0-based index)
        val p99Idx = (Math.ceil(0.99 * latencies.size)).toInt() - 1
        val p99 = latencies[p99Idx]
        assertTrue(
            p99 < 200,
            "p99 latency ${p99}ms exceeded 200ms at 10k pages (p50=${latencies[49]}ms, max=${latencies.last()}ms)"
        )
    }
}
