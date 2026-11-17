// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.diagnostics

import dev.stapler.stelekit.sketch.v2.scene.SceneLimits

/** Where a 500-element frame time was measured. */
enum class PerfTarget { Jvm, Android, WasmJs }

/** The recorded result of one checkpoint evaluation (Task 6.2.9c). */
sealed interface PerfOutcome {
    /** The figure is within the bar (or only one of the two trigger conditions holds); nothing changes. */
    data object Proceed : PerfOutcome

    /** No figure exists for the target yet (an owner-run device or a blocked harness), so no outcome is claimed. */
    data object NotMeasured : PerfOutcome

    /** Both trigger conditions hold: confirm the static layer cache and culling, and use [softElementThreshold] for this target only. */
    data class LowerThreshold(val softElementThreshold: Int) : PerfOutcome

    /** The lowered threshold still missed the bar: the release notes state the element count this target supports. */
    data object RecordSupportedCount : PerfOutcome
}

/**
 * The per-target performance pivot of requirements.md "Abort and pivot criteria" (Story 2.1.3, Task 6.2.9c). The trigger is
 * BOTH conditions: the 500-element p95 exceeds [RELATIVE_BAR] times the empty-canvas reference AND the [CEILING_MS] ceiling.
 * The order is: confirm the static layer cache and viewport culling (already built), lower `SceneLimits.softElementThreshold`
 * for that target only, then record the supported count in the release notes. It narrows a claim for one target and never
 * blocks the JVM/desktop release. Pure: the figures come from the benchmarks and the owner-run device check.
 */
object CanvasPerfCheckpoint {
    const val RELATIVE_BAR = 1.5
    const val CEILING_MS = 24.0

    /** The lowered threshold is the default divided by this, so the degradation ladder starts at half the element count. */
    private const val LOWER_DIVISOR = 2

    /**
     * Evaluates one target. [p95Ms] and [emptyP95Ms] are the 500-element and empty-canvas p95 frame times (null when not
     * measured). [thresholdAlreadyLowered] is true when [limitsFor] was applied and the figure was measured again.
     */
    fun evaluate(p95Ms: Double?, emptyP95Ms: Double?, thresholdAlreadyLowered: Boolean = false, limits: SceneLimits = SceneLimits.Default): PerfOutcome {
        val p95 = p95Ms?.takeIf { it.isFinite() }
        val empty = emptyP95Ms?.takeIf { it.isFinite() }
        return if (p95 == null || empty == null) PerfOutcome.NotMeasured else decide(p95, empty, thresholdAlreadyLowered, limits)
    }

    private fun decide(p95: Double, empty: Double, thresholdAlreadyLowered: Boolean, limits: SceneLimits): PerfOutcome {
        val triggered = p95 > empty * RELATIVE_BAR && p95 > CEILING_MS
        return when {
            !triggered -> PerfOutcome.Proceed
            thresholdAlreadyLowered -> PerfOutcome.RecordSupportedCount
            else -> PerfOutcome.LowerThreshold(limits.softElementThreshold / LOWER_DIVISOR)
        }
    }

    /** [base] with the lowered soft threshold when [outcome] asks for it; any other outcome leaves the limits as they were. */
    fun limitsFor(outcome: PerfOutcome, base: SceneLimits): SceneLimits =
        if (outcome is PerfOutcome.LowerThreshold) base.copy(softElementThreshold = outcome.softElementThreshold) else base
}
