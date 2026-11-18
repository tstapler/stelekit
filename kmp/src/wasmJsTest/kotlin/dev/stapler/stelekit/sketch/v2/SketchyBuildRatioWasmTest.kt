// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.render.SketchTarget
import dev.stapler.stelekit.sketch.v2.render.SketchyAvailability
import dev.stapler.stelekit.sketch.v2.render.SketchyMeasurement
import dev.stapler.stelekit.sketch.v2.render.SketchyStatus
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Task 6.2.9a, the wasmJs half of the build-time rule: building the Sketchy draw list for 500 elements must take at most
 * `SketchyAvailability.SKETCHY_BUILD_BUDGET_MS` (absolute, owner decision 2026-11-17), else the target draws Clean. The pixel
 * half is `LargeSceneDegradationWasmTest`. This test pins `SketchyAvailability.RECORDED` for Web to the measurement. The
 * Sketchy/Clean ratio is printed (informational) with the other figures as `SKETCHY_BUILD_RATIO_WASM`.
 */
class SketchyBuildRatioWasmTest {
    private fun scene(): SketchSceneV2 = (0 until ELEMENTS).fold(SketchSceneV2.empty()) { s, i ->
        val e = RectElement(
            ElementBase(ElementId("g$i"), style = ElementStyle(roughness = 1), seed = i + 1),
            SceneUnit.ofInt((i % COLUMNS) * STEP), SceneUnit.ofInt((i / COLUMNS) * STEP), SceneUnit.ofInt(CELL), SceneUnit.ofInt(CELL),
        )
        s.add(e).getOrNull()!!
    }

    /** Best of [ROUNDS] timings of [BATCH] fresh builds each: the browser clock is coarse (about 0.1 ms), one 500-element build is not. */
    private fun bestNanos(scene: SketchSceneV2, style: RenderStyle): Long = (0 until ROUNDS).minOf {
        val start = TimeSource.Monotonic.markNow()
        repeat(BATCH) { DrawListBuilder(FixedAdvanceTextMetricsProvider()).build(scene, style) }
        start.elapsedNow().inWholeNanoseconds / BATCH
    }

    @Test
    fun sketchyBuild_should_StayUnderTheAbsoluteBudget_For500Elements_OnWasm() {
        val scene = scene()
        bestNanos(scene, RenderStyle.Clean)
        bestNanos(scene, RenderStyle.Sketchy)
        val measurement = SketchyMeasurement(0.0, sketchyBuildNanos = bestNanos(scene, RenderStyle.Sketchy), cleanBuildNanos = bestNanos(scene, RenderStyle.Clean))
        println("SKETCHY_BUILD_RATIO_WASM sketchy=${measurement.sketchyBuildNanos} clean=${measurement.cleanBuildNanos} ratio=${measurement.ratio} sketchyMs=${measurement.sketchyBuildMillis} budgetMs=${SketchyAvailability.SKETCHY_BUILD_BUDGET_MS}")
        assertTrue(measurement.sketchyBuildMillis <= SketchyAvailability.SKETCHY_BUILD_BUDGET_MS, "500-element Sketchy build over the budget: $measurement")
        val measuredUnavailable = SketchyAvailability.assess(measurement) is SketchyStatus.Unavailable
        assertEquals(measuredUnavailable, SketchyAvailability().status(SketchTarget.Web) is SketchyStatus.Unavailable, "RECORDED must follow the measurement: $measurement")
    }

    private companion object {
        const val ELEMENTS = 500
        const val COLUMNS = 25
        const val STEP = 24
        const val CELL = 20
        const val ROUNDS = 9
        const val BATCH = 20
    }
}
