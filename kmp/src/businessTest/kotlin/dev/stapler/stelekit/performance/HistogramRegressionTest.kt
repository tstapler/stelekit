package dev.stapler.stelekit.performance

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HistogramRegressionTest {

    @Test
    fun `cache_block hit rate is recorded and P50 equals recorded value`() = runBlocking {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        val scope = CoroutineScope(Dispatchers.IO)
        val writer = HistogramWriter(database, scope)

        // Simulate 100% hit rate: record 100 as "cache_block" (units: hit-rate percent, 0–100)
        repeat(10) { writer.record("cache_block", durationMs = 100L) }

        var summary: PercentileSummary? = null
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            summary = writer.queryPercentiles("cache_block")
            if (summary != null && summary.sampleCount > 0L) break
            delay(50)
        }

        assertNotNull(summary, "Expected cache_block histogram data after recording samples")
        assertTrue(
            summary.p50Ms == 100L,
            "Expected cache_block P50 = 100 (100% hit rate) but was ${summary.p50Ms}"
        )
    }

    @Test
    fun `navigation P95 stays within 50ms bucket when all samples are 40ms`() = runBlocking {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        val scope = CoroutineScope(Dispatchers.IO)
        val writer = HistogramWriter(database, scope)

        val recordedAt = HistogramWriter.epochMs()
        repeat(100) {
            writer.record("navigation", durationMs = 40L, recordedAt = recordedAt)
        }

        // Poll until the channel consumer has processed at least one sample (max 3s).
        // Channel.BUFFERED capacity is 64, so some of the 100 sends may be dropped, but
        // at least the buffered ones should appear quickly.
        var summary: PercentileSummary? = null
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            summary = writer.queryPercentiles("navigation")
            if (summary != null && summary.sampleCount > 0L) break
            delay(50)
        }

        assertNotNull(summary, "Expected percentile data after recording 100 samples")
        assertTrue(
            summary.p95Ms <= 50L,
            "Expected P95 bucket <= 50ms but was ${summary.p95Ms}ms"
        )
    }
}
