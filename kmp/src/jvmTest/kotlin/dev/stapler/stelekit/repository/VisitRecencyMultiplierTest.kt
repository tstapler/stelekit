package dev.stapler.stelekit.repository

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.performance.HistogramWriter
import kotlin.test.Test
import kotlin.test.assertTrue

class VisitRecencyMultiplierTest {

    private val repo: SqlDelightSearchRepository by lazy {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val db = SteleDatabase(driver)
        SqlDelightSearchRepository(db)
    }

    // ── TC-4: returns ~2.0 for visit at t=now ──────────────────────────────

    @Test
    fun visitRecencyMultiplier_returnsNearTwoForVisitAtNow() {
        val nowMs = HistogramWriter.epochMs()
        val result = repo.visitRecencyMultiplier(nowMs, nowMs)
        assertTrue(result in 1.99..2.01, "Expected ~2.0 for t=0, got $result")
    }

    // ── TC-5: returns 1.0 for lastVisitedMs = 0 (never visited) ────────────

    @Test
    fun visitRecencyMultiplier_returnsOneForNeverVisited() {
        val nowMs = HistogramWriter.epochMs()
        val result = repo.visitRecencyMultiplier(0L, nowMs)
        assertTrue(result == 1.0, "Expected 1.0 for lastVisitedMs=0, got $result")
    }

    // ── TC-6: decays toward 1.0 after many days ─────────────────────────────

    @Test
    fun visitRecencyMultiplier_decaysAfter30Days() {
        val nowMs = HistogramWriter.epochMs()
        val thirtyDaysAgoMs = nowMs - (30L * 24 * 60 * 60 * 1000)
        val result = repo.visitRecencyMultiplier(thirtyDaysAgoMs, nowMs)
        // 30 days >> 3-day half-life: result should be close to 1.0 but still above it
        assertTrue(result > 1.0, "Expected result > 1.0, got $result")
        assertTrue(result < 1.1, "Expected result < 1.1 (close to 1.0 after 30 days), got $result")
    }
}
