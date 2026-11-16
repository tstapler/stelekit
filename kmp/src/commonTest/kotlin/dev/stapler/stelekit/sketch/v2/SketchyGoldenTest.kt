// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.render.CleanStrokeStyle
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.ElementSeeds
import dev.stapler.stelekit.sketch.v2.render.PathSegment
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.render.ScenePt
import dev.stapler.stelekit.sketch.v2.render.SeededRandom
import dev.stapler.stelekit.sketch.v2.render.SequenceSeedSource
import dev.stapler.stelekit.sketch.v2.render.SheetStyleDefaults
import dev.stapler.stelekit.sketch.v2.render.SketchyStrokeStyle
import dev.stapler.stelekit.sketch.v2.render.StrokeGeometry
import dev.stapler.stelekit.sketch.v2.render.StrokeRequest
import dev.stapler.stelekit.sketch.v2.render.StrokeStyleProvider
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.Roundness
import dev.stapler.stelekit.sketch.v2.scene.RoundnessKind
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Story 2.2.1: seeded PRNG and rough.js stroke subset match the JS goldens (seed 1337) on every target. */
class SketchyGoldenTest {
    private val seed = 1337
    private val rectPoints = listOf(ScenePt(0.0, 0.0), ScenePt(100.0, 0.0), ScenePt(100.0, 60.0), ScenePt(0.0, 60.0))

    private fun draw(geometry: StrokeGeometry, roughness: Int = 1, seed: Int = this.seed, angle: Double = 0.0, px: Double = 0.0, py: Double = 0.0) =
        SketchyStrokeStyle.outline(StrokeRequest(geometry, seed, roughness, angle, px, py), listOf(PathSegment.Close))

    private fun assertOps(expected: List<PathSegment>, actual: List<PathSegment>, name: String) {
        assertEquals(expected.size, actual.size, "$name op count")
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            assertEquals(e::class, a::class, "$name op $i kind")
            assertClose(numbers(e), numbers(a), "$name op $i")
        }
    }

    private fun numbers(s: PathSegment): List<Double> = when (s) {
        is PathSegment.MoveTo -> listOf(s.x, s.y)
        is PathSegment.LineTo -> listOf(s.x, s.y)
        is PathSegment.CubicTo -> listOf(s.c1x, s.c1y, s.c2x, s.c2y, s.x, s.y)
        PathSegment.Close -> emptyList()
    }

    private fun assertClose(expected: List<Double>, actual: List<Double>, what: String) {
        for (i in expected.indices) assertTrue(abs(expected[i] - actual[i]) <= TOLERANCE, "$what value $i: expected ${expected[i]} but was ${actual[i]}")
    }

    @Test
    fun seededRandom_should_MatchRoughJsSequence_When_SeededWithKnownValues() {
        val a = SeededRandom(seed)
        val expected = listOf(0.030053000431507826, 0.6883838293142617, 0.9758248287253082, 0.0403073993511498, 0.6784740793518722)
        for (v in expected) assertEquals(v, a.next())
        val negative = SeededRandom(-5)
        for (v in listOf(0.9998876103200018, 0.5748377568088472, 0.9933589198626578)) assertEquals(v, negative.next())
        val big = SeededRandom(Int.MAX_VALUE)
        for (v in listOf(0.9999775220640004, 0.9149675513617694, 0.39867178397253156)) assertEquals(v, big.next())
    }

    @Test
    fun seededRandom_should_TreatZeroLikeOne_When_SeedIsUnset() {
        assertEquals(SeededRandom(1).next(), SeededRandom(0).next())
    }

    @Test
    fun rectangle_should_MatchGolden_When_Seed1337Roughness1() {
        assertOps(RoughGolden.RECTANGLE, draw(StrokeGeometry.Polyline(rectPoints, closed = true)), "rectangle")
    }

    @Test
    fun ellipse_should_MatchGolden_When_Seed1337Roughness1() {
        assertOps(RoughGolden.ELLIPSE, draw(StrokeGeometry.Ellipse(50.0, 30.0, 50.0, 30.0)), "ellipse")
    }

    @Test
    fun line_should_MatchGolden_When_Seed1337Roughness1() {
        assertOps(RoughGolden.LINE, draw(StrokeGeometry.Polyline(listOf(ScenePt(0.0, 0.0), ScenePt(100.0, 60.0)), closed = false)), "line")
    }

    @Test
    fun polygon_should_MatchGolden_When_Seed1337() {
        val pts = listOf(ScenePt(0.0, 0.0), ScenePt(100.0, 10.0), ScenePt(80.0, 70.0), ScenePt(10.0, 50.0))
        assertOps(RoughGolden.POLYGON, draw(StrokeGeometry.Polyline(pts, closed = true)), "polygon")
    }

    @Test
    fun curve_should_MatchGolden_When_FourAndThreePoints() {
        val four = listOf(ScenePt(0.0, 0.0), ScenePt(50.0, 40.0), ScenePt(100.0, 0.0), ScenePt(150.0, 40.0))
        assertOps(RoughGolden.CURVE4, draw(StrokeGeometry.Curve(four)), "curve4")
        assertOps(RoughGolden.CURVE3, draw(StrokeGeometry.Curve(four.take(3))), "curve3")
    }

    @Test
    fun path_should_MatchGolden_When_LinesCubicAndClose() {
        val clean = listOf(
            PathSegment.MoveTo(0.0, 0.0), PathSegment.LineTo(60.0, 0.0),
            PathSegment.CubicTo(70.0, 10.0, 80.0, 30.0, 60.0, 40.0), PathSegment.LineTo(0.0, 40.0), PathSegment.Close,
        )
        val drawn = SketchyStrokeStyle.outline(StrokeRequest(StrokeGeometry.Outline, seed, 1), clean)
        assertOps(RoughGolden.PATH, drawn, "path")
    }

    @Test
    fun roughness2_should_MatchGolden_When_RectangleAndEllipse() {
        assertOps(RoughGolden.RECTANGLE_ROUGHNESS2, draw(StrokeGeometry.Polyline(rectPoints, closed = true), roughness = 2), "rect r2")
        assertOps(RoughGolden.ELLIPSE_ROUGHNESS2, draw(StrokeGeometry.Ellipse(50.0, 30.0, 50.0, 30.0), roughness = 2), "ellipse r2")
    }

    @Test
    fun rotation_should_TurnTheGoldenAboutThePivot_When_AngleIsSet() {
        val turned = draw(StrokeGeometry.Polyline(rectPoints, closed = true), angle = PI / 2.0, px = 50.0, py = 30.0)
        val expected = RoughGolden.RECTANGLE.map { s ->
            fun x(x: Double, y: Double) = 50.0 - (y - 30.0)
            fun y(x: Double, y: Double) = 30.0 + (x - 50.0)
            when (s) {
                is PathSegment.MoveTo -> PathSegment.MoveTo(x(s.x, s.y), y(s.x, s.y))
                is PathSegment.CubicTo -> PathSegment.CubicTo(x(s.c1x, s.c1y), y(s.c1x, s.c1y), x(s.c2x, s.c2y), y(s.c2x, s.c2y), x(s.x, s.y), y(s.x, s.y))
                else -> s
            }
        }
        assertOps(expected, turned, "rotated rectangle")
    }

    @Test
    fun roughnessZero_should_ReturnTheCleanListItself_When_StyleIsSketchy() {
        val clean = listOf(PathSegment.MoveTo(1.0, 2.0), PathSegment.Close)
        val request = StrokeRequest(StrokeGeometry.Polyline(rectPoints, closed = true), seed, 0)
        assertSame(clean, SketchyStrokeStyle.outline(request, clean))
        assertSame(clean, CleanStrokeStyle.outline(request.copy(roughness = 1), clean))
        assertSame(CleanStrokeStyle, StrokeStyleProvider.forStyle(RenderStyle.Clean))
        assertSame(SketchyStrokeStyle, StrokeStyleProvider.forStyle(RenderStyle.Sketchy))
    }

    // -- draw list ------------------------------------------------------------------------------------------------

    private fun u(i: Int) = SceneUnit.ofInt(i)
    private fun p(x: Int, y: Int) = ScenePoint(u(x), u(y))
    private fun base(id: String, roughness: Int, seed: Int = this.seed, fill: String = "transparent", roundness: Roundness? = null) =
        ElementBase(ElementId(id), style = ElementStyle(roughness = roughness, backgroundColor = fill, roundness = roundness), seed = seed)

    private fun scene(vararg elements: SceneElement): SketchSceneV2 {
        var s = SketchSceneV2.empty()
        for (e in elements) s = s.add(e).getOrNull()!!
        return s
    }

    private fun mixed(roughness: Int): SketchSceneV2 = scene(
        RectElement(base("r", roughness, fill = "#a5d8ff"), u(0), u(0), u(100), u(60)),
        RectElement(base("rr", roughness, roundness = Roundness(RoundnessKind.AdaptiveRadius)), u(120), u(0), u(100), u(60)),
        EllipseElement(base("e", roughness), u(50), u(110), u(50), u(30)),
        LineElement(base("l", roughness), listOf(p(0, 160), p(100, 200))),
        ArrowElement(base("a", roughness), listOf(p(120, 160), p(220, 200)), null, null, endArrowhead = Arrowhead.Triangle),
    )

    private fun builder() = DrawListBuilder(FixedAdvanceTextMetricsProvider())

    @Test
    fun drawList_should_EqualCleanByteForByte_When_RoughnessIsZero() {
        val scene = mixed(roughness = 0)
        assertEquals(builder().build(scene, RenderStyle.Clean), builder().build(scene, RenderStyle.Sketchy))
    }

    @Test
    fun drawList_should_MatchGolden_When_SketchyRectangleHasSeed1337() {
        val scene = scene(RectElement(base("r", 1), u(0), u(0), u(100), u(60)))
        val ops = builder().build(scene, RenderStyle.Sketchy)
        assertEquals(1, ops.size)
        val op = ops.single() as DrawOp.PathOp
        assertOps(RoughGolden.RECTANGLE, op.segments, "draw list rectangle")
    }

    @Test
    fun drawList_should_MatchGolden_When_SketchyEllipseHasSeed1337() {
        val scene = scene(EllipseElement(base("e", 1), u(50), u(30), u(50), u(30)))
        val op = builder().build(scene, RenderStyle.Sketchy).single() as DrawOp.PathOp
        assertOps(RoughGolden.ELLIPSE, op.segments, "draw list ellipse")
    }

    @Test
    fun drawList_should_KeepFillUnderTheSketchyStroke_When_ElementHasBackground() {
        val scene = scene(RectElement(base("r", 1, fill = "#a5d8ff"), u(0), u(0), u(100), u(60)))
        val ops = builder().build(scene, RenderStyle.Sketchy).map { it as DrawOp.PathOp }
        assertEquals(2, ops.size)
        assertEquals("#a5d8ff", ops[0].fill)
        assertEquals(null, ops[0].stroke)
        assertEquals(null, ops[1].fill)
        assertTrue(ops[1].stroke != null)
    }

    @Test
    fun drawList_should_DifferFromClean_When_StyleIsSketchyAndRoughnessAboveZero() {
        val scene = mixed(roughness = 1)
        assertNotEquals(builder().build(scene, RenderStyle.Clean), builder().build(scene, RenderStyle.Sketchy))
    }

    @Test
    fun drawList_should_BeIdentical_When_BuiltTwiceWithTheSameSeeds() {
        val scene = mixed(roughness = 2)
        assertEquals(builder().build(scene, RenderStyle.Sketchy), builder().build(scene, RenderStyle.Sketchy))
    }

    @Test
    fun cache_should_RebuildOnStyleChange_When_SwitchingBetweenCleanAndSketchy() {
        val scene = mixed(roughness = 1)
        val b = builder()
        val clean = b.build(scene, RenderStyle.Clean)
        val sketchy = b.build(scene, RenderStyle.Sketchy)
        assertEquals(clean, b.build(scene, RenderStyle.Clean))
        assertEquals(sketchy, b.build(scene, RenderStyle.Sketchy))
    }

    // -- properties -----------------------------------------------------------------------------------------------

    @Test
    fun strokes_should_BeDeterministicAndFiniteAndNearTheShape_When_AnySeedAndRoughness() = runTest {
        checkAll(Arb.int(1..Int.MAX_VALUE), Arb.int(1..3)) { s, r ->
            val geometry = StrokeGeometry.Polyline(rectPoints, closed = true)
            val first = draw(geometry, roughness = r, seed = s)
            assertEquals(first, draw(geometry, roughness = r, seed = s))
            val margin = r * REACH_PER_ROUGHNESS
            for (seg in first) for (v in numbers(seg).chunked(2)) {
                assertTrue(v[0].isFinite() && v[1].isFinite())
                assertTrue(v[0] >= -margin && v[0] <= 100.0 + margin && v[1] >= -margin && v[1] <= 60.0 + margin, "point $v outside margin $margin for seed $s")
            }
        }
    }

    @Test
    fun strokes_should_DifferBySeed_When_SeedsDiffer() = runTest {
        checkAll(Arb.int(1..Int.MAX_VALUE)) { s ->
            val other = if (s == Int.MAX_VALUE) 1 else s + 1
            val geometry = StrokeGeometry.Ellipse(50.0, 30.0, 50.0, 30.0)
            assertNotEquals(draw(geometry, seed = s), draw(geometry, seed = other))
        }
    }

    // -- 2.2.1c: sheet default and seed persistence ----------------------------------------------------------------

    @Test
    fun sheetDefault_should_BeClean_When_SheetIsNew() {
        assertEquals(RenderStyle.Clean, SheetStyleDefaults.Default.renderStyle)
        assertEquals(0, SheetStyleDefaults.Default.styleForNewElement().roughness)
    }

    @Test
    fun sheetDefault_should_StartNewElementsAtArtistRoughness_When_SheetIsSketchy() {
        val sketchy = SheetStyleDefaults(RenderStyle.Sketchy)
        assertEquals(SheetStyleDefaults.ARTIST_ROUGHNESS, sketchy.styleForNewElement(ElementStyle(strokeColor = "#ff0000")).roughness)
        assertEquals("#ff0000", sketchy.styleForNewElement(ElementStyle(strokeColor = "#ff0000")).strokeColor)
    }

    @Test
    fun seeds_should_BeAssignedOnceAndSurviveEdits_When_ElementIsCopied() {
        val source = SequenceSeedSource(42)
        val fresh = ElementSeeds.ensureSeed(ElementBase(ElementId("x")), source)
        assertTrue(fresh.seed > 0)
        val edited = fresh.copy(version = fresh.version + 1, style = fresh.style.copy(strokeColor = "#00ff00"))
        assertSame(edited, ElementSeeds.ensureSeed(edited, source))
        assertEquals(fresh.seed, edited.seed)
        val again = SequenceSeedSource(42)
        assertEquals(fresh.seed, again.nextSeed())
    }

    private companion object {
        const val TOLERANCE = 1e-6
        const val REACH_PER_ROUGHNESS = 8.0
    }
}
