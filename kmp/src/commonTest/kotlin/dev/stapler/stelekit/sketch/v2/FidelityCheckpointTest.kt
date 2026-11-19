// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.diagnostics.FidelityCheckpoint
import dev.stapler.stelekit.sketch.v2.diagnostics.FidelityOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

/** Task 6.2.9d: the thresholds of requirements.md (all six, five of six, four or fewer) and the no-claim case. */
class FidelityCheckpointTest {
    private val names = listOf("shapes-text", "bound-arrows", "frame-group", "image", "freedraw", "legacy-binding")

    private fun results(failing: Set<String>) = names.associateWith { it !in failing }

    @Test
    fun evaluate_should_Proceed_When_AllSixRoundTrip() {
        assertEquals(FidelityOutcome.Proceed, FidelityCheckpoint.evaluate(results(emptySet())))
    }

    @Test
    fun evaluate_should_NarrowTheSubset_When_FiveOrSixButNotAllPass() {
        assertEquals(FidelityOutcome.NarrowSubset(listOf("image")), FidelityCheckpoint.evaluate(results(setOf("image"))))
    }

    @Test
    fun evaluate_should_LabelBetaAndTurnExportOff_When_FourOrFewerPass() {
        val twoFail = setOf("image", "freedraw")
        assertEquals(FidelityOutcome.BetaImportExportOff(listOf("freedraw", "image")), FidelityCheckpoint.evaluate(results(twoFail)))
        assertEquals(FidelityOutcome.BetaImportExportOff(names.sorted()), FidelityCheckpoint.evaluate(results(names.toSet())))
    }

    @Test
    fun evaluate_should_ClaimNothing_When_NotAllSixFixturesHaveAResult() {
        assertEquals(FidelityOutcome.NotMeasured, FidelityCheckpoint.evaluate(emptyMap()))
        assertEquals(FidelityOutcome.NotMeasured, FidelityCheckpoint.evaluate(results(emptySet()).minus("image")))
    }
}
