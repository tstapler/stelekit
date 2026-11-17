// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.constraints

/**
 * The measured facts the per-target tier rests on, and the tier each target gets from them (ADR-003 item 7, D-114).
 * [Recorded] is the Story 4.1.3 result: every target-independent criterion and both time bars met on the 30-seed corpus (JVM
 * stands for desktop and Android). A target with no measurement, such as iOS where the v2 editor is compile-only, is `Hidden`.
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
