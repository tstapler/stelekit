package dev.stapler.stelekit.performance

import dev.stapler.stelekit.db.createTelemetryDatabaseInMemory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryStatsRepositoryTest {

    private val repo = QueryStatsRepository(createTelemetryDatabaseInMemory())

    private fun accum(calls: Long, totalMs: Long, errors: Long = 0, minMs: Long = 1, maxMs: Long = 10, b1: Long = calls) =
        QueryStatsCollector.Accum(calls = calls, errors = errors, totalMs = totalMs, minMs = minMs, maxMs = maxMs, b1 = b1)

    @Test
    fun `upsertBatch with empty map is a no-op`() = runBlocking {
        repo.upsertBatch(emptyMap(), "1.0.0")
        assertEquals(emptyList(), repo.getAllVersions())
    }

    @Test
    fun `upsertBatch inserts rows and splits key into table and operation`() = runBlocking {
        repo.upsertBatch(mapOf("pages:select" to accum(4, 20, errors = 1), "blocks" to accum(1, 2)), "1.0.0")
        val byKey = repo.getStatsByVersion("1.0.0").associateBy { it.tableName to it.operation }
        val pages = byKey.getValue("pages" to "select")
        assertEquals(4, pages.calls); assertEquals(1, pages.errors); assertEquals(20, pages.totalMs)
        assertEquals(5, pages.meanMs)
        assertEquals(1, byKey.getValue("blocks" to "unknown").calls)
    }

    @Test
    fun `upsertBatch merges counters on a second batch for the same key`() = runBlocking {
        repo.upsertBatch(mapOf("pages:select" to accum(2, 10, minMs = 3, maxMs = 7)), "1.0.0")
        repo.upsertBatch(mapOf("pages:select" to accum(3, 30, minMs = 1, maxMs = 20)), "1.0.0")
        val s = repo.getStatsByVersion("1.0.0").single()
        assertEquals(5, s.calls); assertEquals(40, s.totalMs)
        assertEquals(1, s.minMs); assertEquals(20, s.maxMs)
        assertEquals(5, s.b1)
    }

    @Test
    fun `getTopByTotalMs orders by total time and honours limit`() = runBlocking {
        repo.upsertBatch(
            mapOf("a:q" to accum(1, 5), "b:q" to accum(1, 50), "c:q" to accum(1, 20)), "1.0.0",
        )
        assertEquals(listOf("b", "c", "a"), repo.getTopByTotalMs("1.0.0").map { it.tableName })
        assertEquals(listOf("b"), repo.getTopByTotalMs("1.0.0", limit = 1).map { it.tableName })
    }

    @Test
    fun `getTopByCalls orders by call count`() = runBlocking {
        repo.upsertBatch(
            mapOf("a:q" to accum(3, 1), "b:q" to accum(30, 1), "c:q" to accum(10, 1)), "1.0.0",
        )
        assertEquals(listOf("b", "c", "a"), repo.getTopByCalls("1.0.0").map { it.tableName })
    }

    @Test
    fun `stats are isolated per version and getAllVersions lists each once`() = runBlocking {
        repo.upsertBatch(mapOf("a:q" to accum(1, 1)), "1.0.0")
        repo.upsertBatch(mapOf("b:q" to accum(1, 1)), "1.1.0")
        repo.upsertBatch(mapOf("c:q" to accum(1, 1)), "1.1.0")
        assertEquals(setOf("1.0.0", "1.1.0"), repo.getAllVersions().toSet())
        assertEquals(2, repo.getAllVersions().size)
        assertEquals(2, repo.getStatsByVersion("1.1.0").size)
    }

    @Test
    fun `deleteVersion removes only that version`() = runBlocking {
        repo.upsertBatch(mapOf("a:q" to accum(1, 1)), "1.0.0")
        repo.upsertBatch(mapOf("a:q" to accum(1, 1)), "1.1.0")
        repo.deleteVersion("1.0.0")
        assertEquals(listOf("1.1.0"), repo.getAllVersions())
        assertTrue(repo.getStatsByVersion("1.0.0").isEmpty())
    }

    // ── QueryStat.estimatePercentile / meanMs ──

    private fun stat(calls: Long = 0, b1: Long = 0, b5: Long = 0, b16: Long = 0, b50: Long = 0, b100: Long = 0, b500: Long = 0, bInf: Long = 0) =
        QueryStat("v", "t", "o", calls, 0, 0, 0, 0, b1, b5, b16, b50, b100, b500, bInf, 0, 0)

    @Test
    fun `estimatePercentile returns the upper bound of the bucket holding the target rank`() {
        val s = stat(calls = 100, b1 = 50, b5 = 30, b16 = 10, b50 = 5, b100 = 3, b500 = 1, bInf = 1)
        assertEquals(1L, s.estimatePercentile(0.50))   // rank 50 → exactly fills b1
        assertEquals(5L, s.estimatePercentile(0.51))   // rank 51 spills into b5
        assertEquals(5L, s.estimatePercentile(0.80))
        assertEquals(16L, s.estimatePercentile(0.90))
        assertEquals(50L, s.estimatePercentile(0.95))
        assertEquals(100L, s.estimatePercentile(0.98))
        assertEquals(500L, s.estimatePercentile(0.99))
        assertEquals(Long.MAX_VALUE, s.estimatePercentile(1.0))
    }

    @Test
    fun `estimatePercentile with no samples falls through to MAX and meanMs is zero`() {
        val s = stat()
        assertEquals(Long.MAX_VALUE, s.estimatePercentile(0.5))
        assertEquals(0L, s.meanMs)
    }

    @Test
    fun `estimatePercentile clamps the target rank to at least one`() {
        assertEquals(16L, stat(calls = 1, b16 = 1).estimatePercentile(0.0))
    }
}
