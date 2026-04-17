package dev.stapler.stelekit.performance

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HistogramRegressionTest {

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
            Thread.sleep(50)
        }

        assertNotNull(summary, "Expected percentile data after recording 100 samples")
        assertTrue(
            summary.p95Ms <= 50L,
            "Expected P95 bucket <= 50ms but was ${summary.p95Ms}ms"
        )
    }
}
