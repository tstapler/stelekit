// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.FillRequest
import dev.stapler.stelekit.sketch.v2.render.HachureFill
import dev.stapler.stelekit.sketch.v2.render.PathSegment
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.render.ScenePt
import dev.stapler.stelekit.sketch.v2.render.SeededRandom
import dev.stapler.stelekit.sketch.v2.render.SketchyStrokeStyle
import dev.stapler.stelekit.sketch.v2.render.StrokeGeometry
import dev.stapler.stelekit.sketch.v2.render.StrokeRequest
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Story 2.2.2: hachure, cross-hatch and zigzag fills match rough.js 4.6.6 (seed 1337) and stay inside the shape. */
class HachureFillTest {
    private val diamond = listOf(ScenePt(50.0, 0.0), ScenePt(100.0, 30.0), ScenePt(50.0, 60.0), ScenePt(0.0, 30.0))
    private val ellipseGeometry = StrokeGeometry.Ellipse(50.0, 30.0, 50.0, 30.0)
    private val polyline = StrokeGeometry.Polyline(diamond, closed = true)

    private fun stroke(geometry: StrokeGeometry, style: FillStyle) = SketchyStrokeStyle.stroke(
        StrokeRequest(geometry, 1337, 1, fill = FillRequest(style, 8.0)),
        listOf(PathSegment.Close),
    )

    private fun numbers(s: PathSegment): List<Double> = when (s) {
        is PathSegment.MoveTo -> listOf(s.x, s.y)
        is PathSegment.LineTo -> listOf(s.x, s.y)
        is PathSegment.CubicTo -> listOf(s.c1x, s.c1y, s.c2x, s.c2y, s.x, s.y)
        PathSegment.Close -> emptyList()
    }

    private fun assertOps(expected: List<PathSegment>, actual: List<PathSegment>?, name: String) {
        assertNotNull(actual, "$name was not generated")
        assertEquals(expected.size, actual.size, "$name op count")
        for (i in expected.indices) {
            assertEquals(expected[i]::class, actual[i]::class, "$name op $i kind")
            val e = numbers(expected[i])
            val a = numbers(actual[i])
            for (k in e.indices) assertTrue(abs(e[k] - a[k]) <= TOLERANCE, "$name op $i value $k: expected ${e[k]} but was ${a[k]}")
        }
    }

    @Test
    fun hachure_should_MatchGolden_When_DiamondGap8Seed1337() {
        val drawn = stroke(polyline, FillStyle.Hachure)
        assertOps(HachureGolden.DIAMOND_HACHURE_STROKE, drawn.outline, "diamond stroke")
        assertOps(HachureGolden.DIAMOND_HACHURE_FILL, drawn.fill, "diamond hachure")
    }

    @Test
    fun crossHatch_should_MatchGolden_When_DiamondGap8Seed1337() {
        assertOps(HachureGolden.DIAMOND_CROSS_HATCH_FILL, stroke(polyline, FillStyle.CrossHatch).fill, "diamond cross-hatch")
    }

    @Test
    fun zigzag_should_MatchGolden_When_DiamondGap8Seed1337() {
        assertOps(HachureGolden.DIAMOND_ZIGZAG_FILL, stroke(polyline, FillStyle.Zigzag).fill, "diamond zigzag")
    }

    @Test
    fun ellipseHachure_should_MatchGolden_When_Seed1337() {
        val drawn = stroke(ellipseGeometry, FillStyle.Hachure)
        assertOps(HachureGolden.ELLIPSE_HACHURE_STROKE, drawn.outline, "ellipse stroke")
        assertOps(HachureGolden.ELLIPSE_HACHURE_FILL, drawn.fill, "ellipse hachure")
    }

    @Test
    fun hachureLineCount_should_EqualGoldenLineCount_When_DiamondGap8() {
        val random = SeededRandom(1337)
        val lines = HachureFill.lines(listOf(diamond), 8.0, HachureFill.DEFAULT_ANGLE_DEGREES, 1.0) { random.next() }
        val goldenLines = HachureGolden.DIAMOND_HACHURE_FILL.size / OPS_PER_DOUBLE_LINE
        assertEquals(goldenLines, lines.size)
    }

    private fun inside(p: ScenePt, polygon: List<ScenePt>): Boolean {
        var in_ = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) in_ = !in_
            j = i
        }
        return in_
    }

    private fun distanceToEdges(p: ScenePt, polygon: List<ScenePt>): Double {
        var best = Double.MAX_VALUE
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            val ex = a.x + t * dx - p.x
            val ey = a.y + t * dy - p.y
            best = minOf(best, kotlin.math.sqrt(ex * ex + ey * ey))
        }
        return best
    }

    @Test
    fun hatchLines_should_LieInsideTheDiamond_When_AnySizeGapAndAngle() = runTest {
        checkAll(Arb.int(20, 400), Arb.int(20, 400), Arb.int(2, 30), Arb.int(-80, 80)) { wi, hi, gap, angle ->
            val w = wi.toDouble()
            val h = hi.toDouble()
            val poly = listOf(ScenePt(w / 2, 0.0), ScenePt(w, h / 2), ScenePt(w / 2, h), ScenePt(0.0, h / 2))
            val random = SeededRandom(7)
            val lines = HachureFill.lines(listOf(poly), gap.toDouble(), angle.toDouble(), 0.0) { random.next() }
            for (l in lines) {
                for (end in listOf(l.from, l.to)) {
                    // Scan lines run through integer-rounded x, so an end may sit up to a unit off the edge.
                    assertTrue(inside(end, poly) || distanceToEdges(end, poly) <= ROUNDING_SLACK, "hatch end $end outside the diamond")
                }
            }
        }
    }

    @Test
    fun lines_should_BeEmpty_When_ACoordinateIsNotFinite() {
        val poly = listOf(ScenePt(0.0, 0.0), ScenePt(Double.NaN, 5.0), ScenePt(10.0, 10.0))
        assertEquals(0, HachureFill.lines(listOf(poly), 4.0, 0.0, 0.0) { 0.5 }.size)
    }

    @Test
    fun builder_should_EmitHatchBeforeStroke_When_SketchyDiamondIsHachured() {
        val ops = DrawListBuilder(FixedAdvanceTextMetricsProvider()).build(scene(FillStyle.Hachure, roughness = 1), RenderStyle.Sketchy)
        assertEquals(2, ops.size)
        val hatch = ops[0] as DrawOp.PathOp
        val outline = ops[1] as DrawOp.PathOp
        assertEquals("#ffc9c9", hatch.stroke?.color)
        assertEquals(1.0, hatch.stroke?.width)
        assertEquals(null, hatch.fill)
        assertEquals("#1e1e1e", outline.stroke?.color)
        assertEquals(null, outline.fill)
    }

    @Test
    fun builder_should_DrawSolidFillUnderTheStroke_When_FillIsSolid() {
        val ops = DrawListBuilder(FixedAdvanceTextMetricsProvider()).build(scene(FillStyle.Solid, roughness = 1), RenderStyle.Sketchy)
        assertEquals(2, ops.size)
        assertEquals("#ffc9c9", (ops[0] as DrawOp.PathOp).fill)
    }

    @Test
    fun builder_should_MatchClean_When_RoughnessIsZeroWhateverTheFillStyle() {
        for (style in FillStyle.entries) {
            val sc = scene(style, roughness = 0)
            val clean = DrawListBuilder(FixedAdvanceTextMetricsProvider()).build(sc, RenderStyle.Clean)
            val sketchy = DrawListBuilder(FixedAdvanceTextMetricsProvider()).build(sc, RenderStyle.Sketchy)
            assertEquals(clean, sketchy, "roughness 0 with $style")
            assertSame(null, (clean[0] as DrawOp.PathOp).stroke?.takeIf { it.color == "#ffc9c9" })
        }
    }

    private fun scene(fill: FillStyle, roughness: Int): SketchSceneV2 {
        val base = ElementBase(
            id = ElementId("d1"),
            seed = 1337,
            style = ElementStyle(backgroundColor = "#ffc9c9", fillStyle = fill, roughness = roughness),
        )
        val element = DiamondElement(base, unit(0), unit(0), unit(100), unit(60))
        return SketchSceneV2.empty().add(element).getOrNull()!!
    }

    private fun unit(v: Int) = SceneUnit.ofInt(v)

    private companion object {
        const val TOLERANCE = 1e-6
        const val OPS_PER_DOUBLE_LINE = 4
        const val ROUNDING_SLACK = 1.5
    }
}
