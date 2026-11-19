// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.render.CleanStrokeStyle
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.render.SketchTarget
import dev.stapler.stelekit.sketch.v2.render.SketchyAvailability
import dev.stapler.stelekit.sketch.v2.render.SketchyMeasurement
import dev.stapler.stelekit.sketch.v2.render.SketchyStatus
import dev.stapler.stelekit.sketch.v2.render.SketchyStrokeStyle
import dev.stapler.stelekit.sketch.v2.render.StrokeStyleProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Task 6.2.9a: the recorded per-target Sketchy availability is exactly what the checkpoint measured (ADR-004,
 * `implementation/pivots.md`). The rule is the pixel tolerance plus an absolute 4 ms budget for a 500-element Sketchy build
 * (owner decision 2026-11-17); the Sketchy/Clean ratio is informational. wasmJs built it in about 1.1 ms with 0.0 differing
 * pixels, so no target is Unavailable; iOS is unmeasured (BUG-001 blocks its compile on a Linux host).
 */
class SketchyAvailabilityTest {
    private val recorded = SketchyAvailability()

    @Test
    fun web_should_BeAvailable_BecauseItsSketchyBuildIsUnderTheAbsoluteBudget() {
        assertSame(SketchyStatus.Available, recorded.status(SketchTarget.Web))
        assertEquals(RenderStyle.Sketchy, recorded.effective(RenderStyle.Sketchy, SketchTarget.Web))
        assertNull(recorded.notice(RenderStyle.Sketchy, SketchTarget.Web))
        assertSame(SketchyStrokeStyle, StrokeStyleProvider.forTarget(RenderStyle.Sketchy, recorded, SketchTarget.Web))
    }

    @Test
    fun theMeasuredFigures_should_AssessAsAvailable_EvenAtARatioOverTwo() {
        val wasm = SketchyMeasurement(0.0, sketchyBuildNanos = 1_100_000, cleanBuildNanos = 500_000)
        val jvm = SketchyMeasurement(0.0, sketchyBuildNanos = 340_000, cleanBuildNanos = 130_000)
        assertTrue(wasm.ratio > 2.0 && jvm.ratio > 2.0, "the old ratio rule would have switched both off")
        assertSame(SketchyStatus.Available, SketchyAvailability.assess(wasm))
        assertSame(SketchyStatus.Available, SketchyAvailability.assess(jvm))
    }

    @Test
    fun aBuildOverTheBudget_or_APixelDiffOverTolerance_should_AssessAsUnavailable() {
        val slow = SketchyMeasurement(0.0, sketchyBuildNanos = 4_100_000, cleanBuildNanos = 4_000_000)
        val atBudget = SketchyMeasurement(0.0, sketchyBuildNanos = 4_000_000, cleanBuildNanos = 100_000)
        val diff = SketchyMeasurement(0.03, sketchyBuildNanos = 1_000_000, cleanBuildNanos = 500_000)
        assertIs<SketchyStatus.Unavailable>(SketchyAvailability.assess(slow))
        assertSame(SketchyStatus.Available, SketchyAvailability.assess(atBudget))
        assertIs<SketchyStatus.Unavailable>(SketchyAvailability.assess(diff))
    }

    @Test
    fun desktopAndAndroid_should_KeepSketchy_AndNoNoticeShows() {
        for (target in listOf(SketchTarget.Desktop, SketchTarget.Android)) {
            assertSame(SketchyStatus.Available, recorded.status(target), "$target")
            assertEquals(RenderStyle.Sketchy, recorded.effective(RenderStyle.Sketchy, target))
            assertNull(recorded.notice(RenderStyle.Sketchy, target))
            assertSame(SketchyStrokeStyle, StrokeStyleProvider.forTarget(RenderStyle.Sketchy, recorded, target))
        }
    }

    @Test
    fun ios_should_StayAvailable_UntilADeviceRunMeasuresIt() {
        assertSame(SketchyStatus.Available, recorded.status(SketchTarget.Ios))
    }

    @Test
    fun aCleanRequest_should_NeverShowTheNotice_OnAnyTarget() {
        for (target in SketchTarget.entries) assertNull(recorded.notice(RenderStyle.Clean, target), "$target")
    }
}
