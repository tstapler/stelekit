// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.diagnostics.CanvasPerfCheckpoint
import dev.stapler.stelekit.sketch.v2.diagnostics.PerfOutcome
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlin.test.Test
import kotlin.test.assertEquals

/** Task 6.2.9c: the pivot needs BOTH conditions, narrows one target in a fixed order, and never claims an outcome without a figure. */
class CanvasPerfCheckpointTest {
    @Test
    fun evaluate_should_Proceed_When_TheMeasuredJvmFiguresAreWithinTheCeiling() {
        // baselines.md Task 0.1.7b: 500 rounded rects 14.23 ms against an empty canvas of 0.24 ms (over 1.5x, under 24 ms).
        assertEquals(PerfOutcome.Proceed, CanvasPerfCheckpoint.evaluate(p95Ms = 14.23, emptyP95Ms = 0.24))
        // ScenePainterTest: the real painter at 500 elements, 1.82 ms.
        assertEquals(PerfOutcome.Proceed, CanvasPerfCheckpoint.evaluate(p95Ms = 1.82, emptyP95Ms = 0.24))
    }

    @Test
    fun evaluate_should_Proceed_When_OnlyTheCeilingIsExceeded() {
        assertEquals(PerfOutcome.Proceed, CanvasPerfCheckpoint.evaluate(p95Ms = 30.0, emptyP95Ms = 25.0))
    }

    @Test
    fun evaluate_should_LowerTheThresholdOnce_When_BothConditionsHold() {
        val outcome = CanvasPerfCheckpoint.evaluate(p95Ms = 30.0, emptyP95Ms = 2.0)
        assertEquals(PerfOutcome.LowerThreshold(SceneLimits.Default.softElementThreshold / 2), outcome)
        val lowered = CanvasPerfCheckpoint.limitsFor(outcome, SceneLimits.Default)
        assertEquals(2_000, lowered.softElementThreshold)
        assertEquals(SceneLimits.Default.copy(softElementThreshold = 2_000), lowered, "nothing else changes")
        assertEquals(SceneLimits.Default, CanvasPerfCheckpoint.limitsFor(PerfOutcome.Proceed, SceneLimits.Default))
    }

    @Test
    fun evaluate_should_RecordTheSupportedCount_When_TheLoweredThresholdStillMissesTheBar() {
        assertEquals(PerfOutcome.RecordSupportedCount, CanvasPerfCheckpoint.evaluate(p95Ms = 30.0, emptyP95Ms = 2.0, thresholdAlreadyLowered = true))
        assertEquals(PerfOutcome.Proceed, CanvasPerfCheckpoint.evaluate(p95Ms = 10.0, emptyP95Ms = 2.0, thresholdAlreadyLowered = true))
    }

    @Test
    fun evaluate_should_ClaimNothing_When_ADeviceFigureIsMissingOrNotFinite() {
        assertEquals(PerfOutcome.NotMeasured, CanvasPerfCheckpoint.evaluate(p95Ms = null, emptyP95Ms = 0.24))
        assertEquals(PerfOutcome.NotMeasured, CanvasPerfCheckpoint.evaluate(p95Ms = 14.0, emptyP95Ms = null))
        assertEquals(PerfOutcome.NotMeasured, CanvasPerfCheckpoint.evaluate(p95Ms = Double.NaN, emptyP95Ms = 0.24))
    }
}
