// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.diagnostics

/** The recorded result of one `.excalidraw` fidelity evaluation (Task 6.2.9d). */
sealed interface FidelityOutcome {
    /** Every fixture round-trips with exact geometry; the supported subset stands as stated. */
    data object Proceed : FidelityOutcome

    /** No fixture result exists, so no outcome is claimed. */
    data object NotMeasured : FidelityOutcome

    /** Some fixtures failed but more than [FidelityCheckpoint.BETA_AT_OR_BELOW] passed: their element kinds leave the supported subset before release. */
    data class NarrowSubset(val failed: List<String>) : FidelityOutcome

    /** [FidelityCheckpoint.BETA_AT_OR_BELOW] or fewer fixtures passed: import ships labelled beta, export is off by default, and the owner amends metric 7. */
    data class BetaImportExportOff(val failed: List<String>) : FidelityOutcome
}

/**
 * The `.excalidraw` fidelity pivot of requirements.md "Abort and pivot criteria" (Story 5.1.3, Task 6.2.9d): if fewer than all
 * six hand-authored fixtures round-trip with exact geometry for the supported subset, the unmet element kinds leave the stated
 * subset; with four or fewer of six passing, import is labelled beta and export is off by default until fixed. It narrows a
 * claim and never removes a feature from scope. Pure: the pass/fail per fixture comes from the round-trip tests.
 */
object FidelityCheckpoint {
    /** The number of hand-authored fixtures of Task 0.1.5a. */
    const val FIXTURE_COUNT = 6

    /** At or below this many passing fixtures the beta label and export-off apply. */
    const val BETA_AT_OR_BELOW = 4

    /** [results] maps each fixture name to whether it round-tripped with exact geometry; anything but all [FIXTURE_COUNT] fixtures is not measured. */
    fun evaluate(results: Map<String, Boolean>): FidelityOutcome {
        if (results.size != FIXTURE_COUNT) return FidelityOutcome.NotMeasured
        val failed = results.filterValues { !it }.keys.sorted()
        return when {
            failed.isEmpty() -> FidelityOutcome.Proceed
            FIXTURE_COUNT - failed.size <= BETA_AT_OR_BELOW -> FidelityOutcome.BetaImportExportOff(failed)
            else -> FidelityOutcome.NarrowSubset(failed)
        }
    }
}
