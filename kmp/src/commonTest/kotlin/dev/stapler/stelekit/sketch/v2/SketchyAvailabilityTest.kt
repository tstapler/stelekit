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
 * `implementation/pivots.md`). Web's wasmJs build was 2.17x to 2.29x Clean in four runs, over the 2x limit, so Web draws Clean
 * with the notice; every other target keeps Sketchy, and iOS is unmeasured (BUG-001 blocks its compile on a Linux host).
 */
class SketchyAvailabilityTest {
    private val recorded = SketchyAvailability()

    @Test
    fun web_should_BeUnavailable_BecauseItsSketchyBuildMissedTheRatioLimit() {
        val unavailable = assertIs<SketchyStatus.Unavailable>(recorded.status(SketchTarget.Web))
        assertTrue("2x" in unavailable.reason || "2.2x" in unavailable.reason, unavailable.reason)
        assertEquals(RenderStyle.Clean, recorded.effective(RenderStyle.Sketchy, SketchTarget.Web))
        assertEquals("Hand-drawn style is not available on this device yet", recorded.notice(RenderStyle.Sketchy, SketchTarget.Web))
        assertSame(CleanStrokeStyle, StrokeStyleProvider.forTarget(RenderStyle.Sketchy, recorded, SketchTarget.Web))
    }

    @Test
    fun theMeasuredWasmFigures_should_AssessAsUnavailable_AndTheJvmFiguresAsAvailable() {
        val wasm = SketchyMeasurement(0.0, sketchyBuildNanos = 1_040_000, cleanBuildNanos = 460_000)
        val jvm = SketchyMeasurement(0.0, sketchyBuildNanos = 754_260, cleanBuildNanos = 587_298)
        assertIs<SketchyStatus.Unavailable>(SketchyAvailability.assess(wasm))
        assertSame(SketchyStatus.Available, SketchyAvailability.assess(jvm))
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
