// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import arrow.core.getOrElse
import dev.stapler.stelekit.sketch.v2.codec.CanonicalJson
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.FreehandOptions
import dev.stapler.stelekit.sketch.v2.render.FreehandOutline
import dev.stapler.stelekit.sketch.v2.render.FreehandPoint
import dev.stapler.stelekit.sketch.v2.render.PathSegment
import dev.stapler.stelekit.sketch.v2.render.PointSimplifier
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.pair
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Story 2.1.4: the perfect-freehand port matches the JS golden within [TOLERANCE]; RDP caps a stroke; pressure is optional. */
class FreehandGoldenTest {
    private val input: List<FreehandPoint> =
        List(FreehandGolden.INPUT.size / 3) { FreehandPoint(FreehandGolden.INPUT[it * 3], FreehandGolden.INPUT[it * 3 + 1], FreehandGolden.INPUT[it * 3 + 2]) }

    private fun assertMatches(name: String, expected: DoubleArray, options: FreehandOptions) {
        val actual = FreehandOutline.build(input, options)
        assertEquals(expected.size / 2, actual.size, "$name: outline point count")
        for (i in actual.indices) {
            assertTrue(abs(actual[i].x - expected[i * 2]) <= TOLERANCE, "$name[$i].x ${actual[i].x} vs ${expected[i * 2]}")
            assertTrue(abs(actual[i].y - expected[i * 2 + 1]) <= TOLERANCE, "$name[$i].y ${actual[i].y} vs ${expected[i * 2 + 1]}")
        }
    }

    @Test
    fun build_should_MatchTheJsGolden_When_LibraryDefaults() = assertMatches(
        "defaults", FreehandGolden.DEFAULTS_OUTLINE,
        FreehandOptions(size = 16.0, thinning = 0.5, smoothing = 0.5, streamline = 0.5, simulatePressure = false, last = true),
    )

    @Test
    fun build_should_MatchTheJsGolden_When_ExcalidrawOptions() = assertMatches(
        "excalidraw", FreehandGolden.EXCALIDRAW_OUTLINE,
        FreehandOptions.excalidraw(strokeWidth = 2.0, simulatePressure = false),
    )

    private fun inputOf(flat: DoubleArray): List<FreehandPoint> =
        List(flat.size / 3) { if (flat[it * 3 + 2] < 0.0) FreehandPoint(flat[it * 3], flat[it * 3 + 1]) else FreehandPoint(flat[it * 3], flat[it * 3 + 1], flat[it * 3 + 2]) }

    private fun assertExtra(name: String, flat: DoubleArray, expected: DoubleArray, options: FreehandOptions) {
        val actual = FreehandOutline.build(inputOf(flat), options)
        assertEquals(expected.size / 2, actual.size, "$name: outline point count")
        for (i in actual.indices) {
            assertTrue(abs(actual[i].x - expected[i * 2]) <= TOLERANCE, "$name[$i].x ${actual[i].x} vs ${expected[i * 2]}")
            assertTrue(abs(actual[i].y - expected[i * 2 + 1]) <= TOLERANCE, "$name[$i].y ${actual[i].y} vs ${expected[i * 2 + 1]}")
        }
    }

    @Test
    fun build_should_MatchTheJsGolden_When_SharpCornersSimulatedPressureAndShortInputs() {
        val simulated = FreehandOptions.excalidraw(strokeWidth = 2.0, simulatePressure = true)
        assertExtra("zigzag", FreehandGolden.ZIGZAG_INPUT, FreehandGolden.ZIGZAG_OUTLINE, simulated)
        assertExtra("two", FreehandGolden.TWO_INPUT, FreehandGolden.TWO_OUTLINE, simulated)
        assertExtra("one", FreehandGolden.ONE_INPUT, FreehandGolden.ONE_OUTLINE, simulated)
        assertExtra(
            "loose", FreehandGolden.LOOSE_INPUT, FreehandGolden.LOOSE_OUTLINE,
            FreehandOptions(size = 16.0, simulatePressure = false, last = false),
        )
    }

    @Test
    fun build_should_BeEmpty_When_NoPointsOrNoSize() {
        assertTrue(FreehandOutline.build(emptyList(), FreehandOptions()).isEmpty())
        assertTrue(FreehandOutline.build(input, FreehandOptions(size = 0.0)).isEmpty())
    }

    @Test
    fun build_should_BeFiniteAndClosed_When_SimulatingPressureOnAnyStroke() = runTest {
        checkAll(Arb.list(Arb.pair(Arb.int(-500..500), Arb.int(-500..500)), 1..40)) { raw ->
            val out = FreehandOutline.build(raw.map { FreehandPoint(it.first / 4.0, it.second / 4.0) }, FreehandOptions.excalidraw(2.0, simulatePressure = true))
            assertTrue(out.all { it.x.isFinite() && it.y.isFinite() }, "non-finite outline for $raw")
            assertTrue(out.isNotEmpty(), "empty outline for $raw")
        }
    }

    private fun stroke(n: Int): List<ScenePoint> = List(n) {
        // 1/16 grid values, a slow wave so RDP has real work to do
        val x = it / 16.0
        val y = ((it % WAVE_PERIOD) - WAVE_PERIOD / 2) / 4.0
        ScenePoint(SceneUnit.of(x).getOrElse { error("coord") }, SceneUnit.of(y).getOrElse { error("coord") })
    }

    @Test
    fun simplify_should_CapAt2000PointsAndStayUnder64KB_When_20000PointStroke() {
        val points = stroke(RAW_POINTS)
        val pressures = List(points.size) { 0.25 + (it % PRESSURE_STEPS) / 1000.0 }
        val out = PointSimplifier.simplify(points, pressures)
        assertTrue(out.points.size <= SceneLimits.Default.maxPointsPerElement, "kept ${out.points.size}")
        assertEquals(points.first(), out.points.first())
        assertEquals(points.last(), out.points.last())
        assertEquals(out.points.size, out.pressures.size)
        val bytes = out.points.indices.sumOf { i ->
            val x = CanonicalJson.formatDouble(out.points[i].x.value).getOrElse { error("x") }
            val y = CanonicalJson.formatDouble(out.points[i].y.value).getOrElse { error("y") }
            val p = CanonicalJson.formatDouble(out.pressures[i]).getOrElse { error("p") }
            x.length + y.length + p.length + SEPARATORS_PER_POINT
        }
        assertTrue(bytes < SIXTY_FOUR_KB, "stored points are $bytes bytes")
    }

    @Test
    fun simplify_should_ForceSimulatedPressure_When_PressuresAreAbsentOrMismatched() {
        val points = stroke(SHORT_POINTS)
        for (pressures in listOf(emptyList(), List(points.size - 1) { 0.5 })) {
            val out = PointSimplifier.simplify(points, pressures)
            assertTrue(out.simulatePressure)
            assertTrue(out.pressures.isEmpty())
        }
        val measured = PointSimplifier.simplify(points, List(points.size) { 0.5 })
        assertTrue(!measured.simulatePressure)
    }

    @Test
    fun simplify_should_KeepAnOrderedSubsequenceWithinTheCap_When_AnyStroke() = runTest {
        checkAll(Arb.list(Arb.pair(Arb.int(-400..400), Arb.int(-400..400)), 0..300)) { raw ->
            val points = raw.map { ScenePoint(SceneUnit.ofInt(it.first), SceneUnit.ofInt(it.second)) }
            val tight = SceneLimits(maxPointsPerElement = TIGHT_CAP)
            val out = PointSimplifier.simplify(points, emptyList(), tight)
            assertTrue(out.points.size <= maxOf(TIGHT_CAP, points.size.coerceAtMost(2)))
            if (points.isNotEmpty()) {
                assertEquals(points.first(), out.points.first())
                assertEquals(points.last(), out.points.last())
            }
            var cursor = 0
            for (p in out.points) {
                while (cursor < points.size && points[cursor] != p) cursor++
                assertTrue(cursor < points.size, "not an ordered subsequence")
                cursor++
            }
        }
    }

    @Test
    fun drawList_should_FillTheOutlineWithTheStrokeColour_When_FreedrawElement() {
        val points = stroke(SHORT_POINTS)
        val element = FreedrawElement(ElementBase(ElementId("fd"), seed = 7), points, simulatePressure = true)
        val scene = SketchSceneV2.empty().add(element).getOrNull()
        assertNotNull(scene)
        val ops = DrawListBuilder(FixedAdvanceTextMetricsProvider()).build(scene, RenderStyle.Clean)
        val op = assertIs<DrawOp.PathOp>(ops.single())
        assertEquals(null, op.stroke)
        assertEquals(element.base.style.strokeColor, op.fill)
        assertIs<PathSegment.MoveTo>(op.segments.first())
        assertIs<PathSegment.Close>(op.segments.last())
    }

    private companion object {
        const val TOLERANCE = 1e-6
        const val RAW_POINTS = 20_000
        const val SHORT_POINTS = 30
        const val WAVE_PERIOD = 40
        const val PRESSURE_STEPS = 500
        const val SEPARATORS_PER_POINT = 6
        const val SIXTY_FOUR_KB = 64 * 1024
        const val TIGHT_CAP = 8
    }
}
