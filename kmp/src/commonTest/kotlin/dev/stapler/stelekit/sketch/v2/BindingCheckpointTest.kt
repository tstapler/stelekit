// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.diagnostics.BindingCheckpoint
import dev.stapler.stelekit.sketch.v2.diagnostics.BindingOperation
import dev.stapler.stelekit.sketch.v2.diagnostics.BindingOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

/** Task 6.2.9b: the rule of requirements.md (all held, some failed, dangling blocks release, no claim without all three). */
class BindingCheckpointTest {
    private fun results(failing: Set<BindingOperation>) = BindingOperation.entries.associateWith { it !in failing }

    @Test
    fun evaluate_should_Proceed_When_TheInvariantHeldOverEveryOperation() {
        assertEquals(BindingOutcome.Proceed, BindingCheckpoint.evaluate(results(emptySet())))
    }

    @Test
    fun evaluate_should_NarrowToBindOnDrop_When_AnyOperationFailed() {
        val failing = setOf(BindingOperation.GroupTransform)
        assertEquals(BindingOutcome.NarrowToBindOnDrop(failing), BindingCheckpoint.evaluate(results(failing)))
    }

    @Test
    fun evaluate_should_BlockRelease_When_ASceneHasADanglingIdAfterReconcile() {
        assertEquals(BindingOutcome.BlocksRelease, BindingCheckpoint.evaluate(results(emptySet()), danglingAfterReconcile = true))
        assertEquals(BindingOutcome.BlocksRelease, BindingCheckpoint.evaluate(emptyMap(), danglingAfterReconcile = true))
    }

    @Test
    fun evaluate_should_ClaimNothing_When_AnOperationHasNoResult() {
        assertEquals(BindingOutcome.NotMeasured, BindingCheckpoint.evaluate(emptyMap()))
        assertEquals(BindingOutcome.NotMeasured, BindingCheckpoint.evaluate(results(emptySet()).minus(BindingOperation.Paste)))
    }
}
