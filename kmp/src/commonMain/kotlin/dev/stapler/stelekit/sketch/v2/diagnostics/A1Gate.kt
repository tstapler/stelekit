// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.diagnostics

/** What the owner recorded after drawing one diagram in the rendered slice and in excalidraw.com (Task 6.2.4e). */
data class A1GateRun(
    val finished: Boolean,
    val wouldContinue: Boolean,
    val inAppSeconds: Double,
    val excalidrawSeconds: Double,
    val mustFixBlockers: Int,
)

/** The A1 gate result, in precedence order UNMEASURED, PIVOT, ADJUST, PROCEED (requirements.md "A1 go/no-go gate"). */
sealed interface A1GateOutcome {
    /** No listed item is reachable under the row's ceiling and platforms: no run exists and nothing of the gated set is released. */
    data object UnmeasuredUnderCap : A1GateOutcome

    /** A reachable item exists but the owner has not recorded a run (or recorded unusable times): no verdict is claimed. */
    data object NotRun : A1GateOutcome

    data object Pivot : A1GateOutcome

    /** Finished and would continue, but the ratio exceeds the bar or [blockers] must-fix defects were named. */
    data class Adjust(val ratio: Double, val blockers: Int) : A1GateOutcome

    data class Proceed(val ratio: Double) : A1GateOutcome
}

/**
 * The paired-measurement gate of requirements.md "A1 go/no-go gate" (Task 6.2.4e, D-54). Pure and owner-fed: nothing here
 * measures a time. The gate is n = 1 by design and the ratio bar is INFERRED and tunable, so it is a named constant.
 */
object A1Gate {
    /** In-app time at most this many times the excalidraw.com time of the same diagram. */
    const val RATIO_BAR = 2.0

    /** [anyReachableItem] is whether the owner listed an item reachable under the row's ceiling and platforms. */
    fun evaluate(anyReachableItem: Boolean, run: A1GateRun?): A1GateOutcome =
        when {
            !anyReachableItem -> A1GateOutcome.UnmeasuredUnderCap
            run == null -> A1GateOutcome.NotRun
            !run.finished || !run.wouldContinue -> A1GateOutcome.Pivot
            !run.hasUsableTimes() -> A1GateOutcome.NotRun
            else -> decide(run)
        }

    private fun A1GateRun.hasUsableTimes() = inAppSeconds.isFinite() && excalidrawSeconds.isFinite() && inAppSeconds > 0.0 && excalidrawSeconds > 0.0

    private fun decide(run: A1GateRun): A1GateOutcome {
        val ratio = run.inAppSeconds / run.excalidrawSeconds
        return if (ratio > RATIO_BAR || run.mustFixBlockers > 0) A1GateOutcome.Adjust(ratio, run.mustFixBlockers) else A1GateOutcome.Proceed(ratio)
    }
}
