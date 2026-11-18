// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.constraints

/**
 * The measured facts the per-target tier rests on, and the tier each target gets from them (ADR-003 item 7, D-114).
 * [Recorded] is NOT a measurement: the A1 gate was owner-waived, the verdict is UNMEASURED, and every flag in [Recorded] is a
 * hard-coded `true` placeholder for every target. It only fixes which targets are offered the full solver today (JVM stands for
 * desktop and Android). A target with no row, such as iOS where the v2 editor is compile-only, is `Hidden`.
 * Production reads the row of its own target (`SketchEditorSession` passes `WasmJs` for the web keymap, `Jvm` otherwise);
 * `HostServices`' `Jvm` default only serves tests and hosts built without a session.
 * Re-measuring means editing [Recorded] and `SolverTierFactsTest`, never a call site.
 */
class SolverTierFacts(
    private val targetIndependentCriteriaMet: Boolean,
    private val measurements: Map<SolverTarget, TargetMeasurement>,
) {
    private val tiers: Map<SolverTarget, SolverTier> by lazy { SolverTierPolicy.decide(targetIndependentCriteriaMet, measurements) }

    /** The tier of [target]; null (no solver target, no measurement) is `Hidden`. */
    fun tierOf(target: SolverTarget?): SolverTier = target?.let { tiers[it] } ?: SolverTier.Hidden

    companion object {
        val Recorded = SolverTierFacts(
            targetIndependentCriteriaMet = true,
            measurements = SolverTarget.entries.associateWith {
                TargetMeasurement(fullSetTimeMet = true, geometricOnlySetTimeMet = true, geometricOnlySetResidualMet = true)
            },
        )
    }
}
