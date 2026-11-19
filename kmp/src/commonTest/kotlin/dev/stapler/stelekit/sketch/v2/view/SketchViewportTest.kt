// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.view

import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 3.1.2 (UX-09): zoom keeps the focal point, is clamped to the SceneLimits range, fit-to-content is one step. */
class SketchViewportTest {
    private val limits = SceneLimits.Default
    private val tolerance = 1e-6

    /** Finite doubles on a 0.01 grid in [lo, hi]; Arb.double would also yield NaN and infinities. */
    private fun finite(lo: Double, hi: Double): Arb<Double> = Arb.int((lo * GRID).toInt()..(hi * GRID).toInt()).map { it / GRID }

    @Test
    fun zoomBy_should_KeepScenePointUnderAnchor() = runTest {
        checkAll(finite(0.05, 20.0), finite(-500.0, 500.0), finite(0.2, 5.0)) { z, anchor, factor ->
            val v = SketchViewport.of(zoom = z, panX = 30.0, panY = -40.0)
            val a = ScreenPx(anchor, anchor / 2)
            val before = v.toScene(a)
            val after = v.zoomBy(a, factor).toScene(a)
            assertTrue(abs(before.x.value - after.x.value) < tolerance * maxOf(1.0, abs(before.x.value)), "x $before $after")
            assertTrue(abs(before.y.value - after.y.value) < tolerance * maxOf(1.0, abs(before.y.value)), "y $before $after")
        }
    }

    @Test
    fun zoom_should_ClampToOnePercentAndThreeThousandPercent() {
        val v = SketchViewport.of()
        val origin = ScreenPx(0.0, 0.0)
        assertEquals(limits.maxZoomPercent / 100.0, v.zoomBy(origin, 1e9).zoom)
        assertEquals(limits.minZoomPercent / 100.0, v.zoomBy(origin, 1e-9).zoom)
        assertEquals(v, v.zoomBy(origin, Double.NaN))
        assertEquals(v, v.zoomBy(origin, -2.0))
    }

    @Test
    fun toScene_should_RoundTripWithToScreen() = runTest {
        checkAll(finite(-1000.0, 1000.0), finite(-1000.0, 1000.0)) { x, y ->
            val v = SketchViewport.of(zoom = 2.5, panX = 12.0, panY = -7.0)
            val s = ScreenPx(x, y)
            val back = v.toScreen(v.toScene(s))
            assertTrue(abs(back.x - x) < tolerance && abs(back.y - y) < tolerance, "$s -> $back")
        }
    }

    @Test
    fun toScene_should_ReturnAValidPoint_When_ScreenInputIsNotFinite() {
        val p = SketchViewport.of().toScene(ScreenPx(Double.NaN, Double.POSITIVE_INFINITY))
        assertEquals(0.0, p.x.value)
        assertEquals(SceneLimits.Default.maxCoord, p.y.value)
    }

    @Test
    fun toScene_should_ClampToCoordinateBound_When_FarOutside() {
        val v = SketchViewport.of(zoom = limits.minZoomPercent / 100.0)
        val p = v.toScene(ScreenPx(1e18, -1e18))
        assertEquals(limits.maxCoord, p.x.value)
        assertEquals(-limits.maxCoord, p.y.value)
    }

    @Test
    fun fit_should_CentreContentInOneStep_And_ResetWhenEmpty() {
        val rect = RectElement(
            ElementBase(ElementId("r")),
            SceneUnit.ofInt(100), SceneUnit.ofInt(100), SceneUnit.ofInt(200), SceneUnit.ofInt(100),
        )
        val bounds = SceneBounds.of(listOf(rect))!!
        val v = SketchViewport.of().fit(bounds, viewWidth = 440.0, viewHeight = 300.0, paddingPx = 20.0)
        assertEquals(2.0, v.zoom)
        val topLeft = v.toScreen(dev.stapler.stelekit.sketch.v2.scene.ScenePoint(rect.x, rect.y))
        assertEquals(20.0, topLeft.x)
        assertEquals(50.0, topLeft.y)
        assertEquals(1.0, SketchViewport.of(zoom = 4.0, panX = 9.0).fit(null, 100.0, 100.0, 0.0).zoom)
    }

    @Test
    fun boundsOfRotatedBox_should_GrowWithAngle() {
        val rect = RectElement(
            ElementBase(ElementId("r")),
            SceneUnit.ofInt(0), SceneUnit.ofInt(0), SceneUnit.ofInt(100), SceneUnit.ofInt(0),
            angle = kotlin.math.PI / 2,
        )
        val b = SceneBounds.of(rect)!!
        assertTrue(abs(b.width) < tolerance && abs(b.height - 100.0) < tolerance, "$b")
    }

    private companion object {
        const val GRID = 100.0
    }
}
