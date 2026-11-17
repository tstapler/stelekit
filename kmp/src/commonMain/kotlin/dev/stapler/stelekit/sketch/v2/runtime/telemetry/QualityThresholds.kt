// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.telemetry

/** How one counter row of the flag-exit table reads. Never decides anything: the owner records the flip (Task 6.2.5d, D-48). */
enum class ThresholdReading { Met, NotMet, InsufficientData }

/** The stop-ship rows over the recent windows of [QualitySignals]. */
data class StopShipReading(val crashes: Boolean, val noConvergence: Boolean)

/**
 * The integer thresholds of requirements.md "Flag and legacy-editor exit criteria", all in integer arithmetic. A reading is
 * advice for the owner's decision in the ADR-005 index; nothing flips automatically. The data-loss row is the owner's log
 * (`quality-signals.md`), not a counter, so it is not here.
 */
object QualityThresholds {
    /** Flip: at least this many v2 sessions, then at most `sessions / CRASH_DIVISOR` crashes. */
    const val MIN_SESSIONS = 50L
    const val CRASH_DIVISOR = 100L

    /** Flip: at least this many committed solves, then at most `solves / NO_CONVERGENCE_DIVISOR` that did not converge. */
    const val MIN_SOLVES = 200L
    const val NO_CONVERGENCE_DIVISOR = 50L

    /** Stop-ship: this many crashes among the last [QualitySignals.RECENT_SESSIONS] sessions. */
    const val STOP_SHIP_CRASHES = 2

    /** Stop-ship: this many `NoConvergence` among the last [QualitySignals.RECENT_SOLVES] solves. */
    const val STOP_SHIP_NO_CONVERGENCE = 11

    fun crashRow(counts: QualityCounts): ThresholdReading = row(counts.v2Sessions, MIN_SESSIONS, counts.crashes, CRASH_DIVISOR)

    fun noConvergenceRow(counts: QualityCounts): ThresholdReading =
        row(counts.solves, MIN_SOLVES, counts.noConvergence, NO_CONVERGENCE_DIVISOR)

    /** Stop-ship rows have no minimum n: fewer than the window simply reads fewer entries. */
    fun stopShip(recentSessions: List<Boolean>, recentSolves: List<Boolean>): StopShipReading = StopShipReading(
        crashes = recentSessions.takeLast(QualitySignals.RECENT_SESSIONS).count { it } >= STOP_SHIP_CRASHES,
        noConvergence = recentSolves.takeLast(QualitySignals.RECENT_SOLVES).count { it } >= STOP_SHIP_NO_CONVERGENCE,
    )

    private fun row(n: Long, minN: Long, bad: Long, divisor: Long): ThresholdReading = when {
        n < minN -> ThresholdReading.InsufficientData
        bad <= n / divisor -> ThresholdReading.Met
        else -> ThresholdReading.NotMet
    }
}
