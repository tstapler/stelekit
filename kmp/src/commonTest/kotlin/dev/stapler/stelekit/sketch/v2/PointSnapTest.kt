// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.snap.PointSnap
import dev.stapler.stelekit.sketch.v2.snap.PointSnapKind
import dev.stapler.stelekit.sketch.v2.snap.PointSnapKinds
import dev.stapler.stelekit.sketch.v2.snap.PointSnapProbe
import dev.stapler.stelekit.sketch.v2.tools.elId
import dev.stapler.stelekit.sketch.v2.tools.sceneOfElements
import dev.stapler.stelekit.sketch.v2.tools.sp
import dev.stapler.stelekit.sketch.v2.tools.su
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure geometry of the endpoint, midpoint, intersection, perpendicular, tangent and centre snaps (epic E4). */
class PointSnapTest {
    private val tolerance = 6.0

    private fun line(id: String, vararg pts: Pair<Int, Int>) = LineElement(ElementBase(elId(id)), pts.map { sp(it.first, it.second) })

    private fun probe(
        scene: SketchSceneV2,
        x: Double,
        y: Double,
        kinds: PointSnapKinds = PointSnapKinds.All,
        anchor: Pair<Int, Int>? = null,
        tol: Double = tolerance,
    ): PointSnapProbe = PointSnap.probe(scene, SpatialIndex().also { it.rebuild(scene) }, x, y, tol, kinds, anchor?.let { sp(it.first, it.second) })

    private fun only(kind: PointSnapKind) = PointSnapKinds.None.with(kind, true)

    /** Distance from (x, y) to the infinite line through the two points. */
    private fun offLine(x: Double, y: Double, ax: Int, ay: Int, bx: Int, by: Int): Double =
        abs((bx - ax) * (ay - y) - (ax - x) * (by - ay)) / hypot((bx - ax).toDouble(), (by - ay).toDouble())

    // ---- intersections ---------------------------------------------------------------------------------------------

    @Test
    fun intersection_should_LieOnBothLines_When_TheyCrossNearThePointer() {
        val rnd = Random(SEED)
        var checked = 0
        repeat(ITERATIONS) {
            val a = List(4) { rnd.nextInt(-200, 200) }
            val b = List(4) { rnd.nextInt(-200, 200) }
            val scene = sceneOfElements(line("a", a[0] to a[1], a[2] to a[3]), line("b", b[0] to b[1], b[2] to b[3]))
            val crossing = crossing(a, b) ?: return@repeat
            val hit = assertNotNull(probe(scene, crossing.first + rnd.nextDouble(-2.0, 2.0), crossing.second + rnd.nextDouble(-2.0, 2.0), only(PointSnapKind.Intersection)).hit, "crossing of $a and $b missed")
            assertEquals(PointSnapKind.Intersection, hit.kind)
            assertTrue(offLine(hit.x, hit.y, a[0], a[1], a[2], a[3]) < GEOMETRY_EPS, "not on line a: $a $b -> $hit")
            assertTrue(offLine(hit.x, hit.y, b[0], b[1], b[2], b[3]) < GEOMETRY_EPS, "not on line b: $a $b -> $hit")
            assertTrue(hit.distance <= tolerance)
            checked++
        }
        assertTrue(checked > ITERATIONS / 20, "too few crossings were generated to mean anything: $checked")
    }

    private fun crossing(a: List<Int>, b: List<Int>): Pair<Double, Double>? {
        val d = (a[2] - a[0]) * (b[3] - b[1]) - (a[3] - a[1]) * (b[2] - b[0])
        if (d == 0) return null
        val t = ((b[0] - a[0]) * (b[3] - b[1]) - (b[1] - a[1]) * (b[2] - b[0])).toDouble() / d
        val u = ((b[0] - a[0]) * (a[3] - a[1]) - (b[1] - a[1]) * (a[2] - a[0])).toDouble() / d
        if (t !in 0.0..1.0 || u !in 0.0..1.0) return null
        return (a[0] + t * (a[2] - a[0])) to (a[1] + t * (a[3] - a[1]))
    }

    @Test
    fun intersection_should_BeFound_When_ALineCrossesARectangleEdge() {
        val scene = sceneOfElements(RectElement(ElementBase(elId("r")), su(0), su(0), su(100), su(80)), line("l", -50 to 40, 150 to 40))
        val hit = assertNotNull(probe(scene, 101.0, 41.0, only(PointSnapKind.Intersection)).hit)
        assertEquals(100.0, hit.x, GEOMETRY_EPS)
        assertEquals(40.0, hit.y, GEOMETRY_EPS)
    }

    @Test
    fun intersection_should_NotBeOffered_When_TheSegmentsOnlyMeetWhenExtended() {
        val scene = sceneOfElements(line("a", 0 to 0, 40 to 0), line("b", 100 to -50, 100 to 50))
        assertNull(probe(scene, 100.0, 0.0, only(PointSnapKind.Intersection)).hit)
    }

    // ---- endpoints, midpoints, centres -----------------------------------------------------------------------------

    @Test
    fun endpoint_should_BeTheVertexOfALine_AndTheCornersOfARotatedRectangle() {
        val scene = sceneOfElements(line("a", 0 to 0, 100 to 0))
        assertEquals(PointSnapKind.Endpoint, probe(scene, 3.0, 2.0).hit?.kind)
        val turned = RectElement(ElementBase(elId("r")), su(0), su(0), su(100), su(100), angle = PI / 2)
        val corner = assertNotNull(probe(sceneOfElements(turned), 100.0 + 2, 2.0, only(PointSnapKind.Endpoint)).hit)
        assertEquals(100.0, corner.x, GEOMETRY_EPS)
        assertEquals(0.0, corner.y, GEOMETRY_EPS)
    }

    @Test
    fun midpoint_should_BeTheMiddleOfASegment_AndCentreTheMiddleOfACircle() {
        val scene = sceneOfElements(line("a", 0 to 0, 100 to 40), EllipseElement(ElementBase(elId("c")), su(300), su(300), su(30), su(30)))
        val mid = assertNotNull(probe(scene, 52.0, 19.0).hit)
        assertEquals(PointSnapKind.Midpoint, mid.kind)
        assertEquals(50.0, mid.x, GEOMETRY_EPS)
        assertEquals(20.0, mid.y, GEOMETRY_EPS)
        assertEquals(PointSnapKind.Centre, probe(scene, 302.0, 298.0).hit?.kind)
    }

    @Test
    fun kinds_should_PreferTheHigherPriorityKind_EvenWhenALowerOneIsNearer() {
        val scene = sceneOfElements(line("a", -4 to 0, 4 to 0))
        // The pointer sits 0.5 from the midpoint and 3.5 from an endpoint: both are within 6, the endpoint outranks.
        val hit = assertNotNull(probe(scene, 0.5, 0.0).hit)
        assertEquals(PointSnapKind.Endpoint, hit.kind)
    }

    @Test
    fun disabledKinds_should_NeverBeReturned() {
        val rnd = Random(SEED)
        repeat(ITERATIONS) {
            val scene = sceneOfElements(line("a", rnd.nextInt(-50, 50) to rnd.nextInt(-50, 50), rnd.nextInt(-50, 50) to rnd.nextInt(-50, 50)), line("b", rnd.nextInt(-50, 50) to rnd.nextInt(-50, 50), rnd.nextInt(-50, 50) to rnd.nextInt(-50, 50)))
            val kinds = PointSnapKinds(PointSnapKind.entries.filterTo(HashSet()) { rnd.nextBoolean() })
            val hit = probe(scene, rnd.nextDouble(-50.0, 50.0), rnd.nextDouble(-50.0, 50.0), kinds, anchor = 0 to 0, tol = 20.0).hit
            assertTrue(hit == null || hit.kind in kinds, "$hit not in $kinds")
        }
        assertNull(probe(sceneOfElements(line("a", 0 to 0, 10 to 0)), 0.0, 0.0, PointSnapKinds.None).hit)
    }

    // ---- perpendicular and tangent ---------------------------------------------------------------------------------

    @Test
    fun perpendicular_should_BeTheFootFromTheAnchor_OnlyWhenItFallsOnTheSegment() {
        val scene = sceneOfElements(line("h", 0 to 0, 200 to 0))
        val hit = assertNotNull(probe(scene, 52.0, 3.0, only(PointSnapKind.Perpendicular), anchor = 50 to 100).hit)
        assertEquals(50.0, hit.x, GEOMETRY_EPS)
        assertEquals(0.0, hit.y, GEOMETRY_EPS)
        assertNull(probe(scene, 252.0, 0.0, only(PointSnapKind.Perpendicular), anchor = 250 to 100).hit, "the foot at x=250 is past the segment end")
        assertNull(probe(scene, 52.0, 3.0, only(PointSnapKind.Perpendicular)).hit, "no anchor, no perpendicular")
    }

    @Test
    fun tangent_should_TouchTheCircle_AtARightAngleToTheRadius() {
        val rnd = Random(SEED)
        var checked = 0
        repeat(ITERATIONS) {
            val r = rnd.nextInt(20, 80)
            val circle = EllipseElement(ElementBase(elId("c")), su(0), su(0), su(r), su(r))
            val anchor = rnd.nextInt(150, 300).let { d -> val a = rnd.nextDouble(0.0, 2 * PI); (d * cos(a)).toInt() to (d * sin(a)).toInt() }
            val d = hypot(anchor.first.toDouble(), anchor.second.toDouble())
            val base = kotlin.math.atan2(anchor.second.toDouble(), anchor.first.toDouble())
            val t = base + kotlin.math.acos(r / d)
            val hit = probe(sceneOfElements(circle), r * cos(t) + rnd.nextDouble(-1.0, 1.0), r * sin(t) + rnd.nextDouble(-1.0, 1.0), only(PointSnapKind.Tangent), anchor = anchor).hit ?: return@repeat
            assertEquals(r.toDouble(), hypot(hit.x, hit.y), GEOMETRY_EPS, "not on the circle")
            val dot = (hit.x - 0.0) * (hit.x - anchor.first) + (hit.y - 0.0) * (hit.y - anchor.second)
            assertEquals(0.0, dot, GEOMETRY_EPS * r * d, "radius and tangent line are not perpendicular")
            checked++
        }
        assertTrue(checked > ITERATIONS / 2)
    }

    @Test
    fun tangent_should_NotBeOffered_ForAnAnchorInsideTheCircle_OrAnEllipse() {
        val circle = sceneOfElements(EllipseElement(ElementBase(elId("c")), su(0), su(0), su(50), su(50)))
        assertNull(probe(circle, 50.0, 0.0, only(PointSnapKind.Tangent), anchor = 10 to 10).hit)
        val oval = sceneOfElements(EllipseElement(ElementBase(elId("e")), su(0), su(0), su(50), su(30)))
        assertNull(probe(oval, 12.0, 48.0, only(PointSnapKind.Tangent), anchor = 200 to 0).hit)
    }

    // ---- ordering, reach and cost ----------------------------------------------------------------------------------

    @Test
    fun result_should_NotDependOnTheOrderElementsWereAdded() {
        val rnd = Random(SEED)
        repeat(ITERATIONS / 4) {
            val elements: List<SceneElement> = List(8) { i -> line("l$i", rnd.nextInt(-40, 40) to rnd.nextInt(-40, 40), rnd.nextInt(-40, 40) to rnd.nextInt(-40, 40)) }
            val x = rnd.nextDouble(-30.0, 30.0)
            val y = rnd.nextDouble(-30.0, 30.0)
            val forward = probe(sceneOfElements(*elements.toTypedArray()), x, y, anchor = 5 to 90, tol = 25.0)
            val shuffled = probe(sceneOfElements(*elements.shuffled(rnd).toTypedArray()), x, y, anchor = 5 to 90, tol = 25.0)
            assertEquals(forward.hit, shuffled.hit)
        }
    }

    @Test
    fun hit_should_AlwaysBeWithinTolerance_AndBeTheNearestOfItsKind() {
        val rnd = Random(SEED)
        repeat(ITERATIONS) {
            val scene = sceneOfElements(*Array(6) { i -> line("l$i", rnd.nextInt(-60, 60) to rnd.nextInt(-60, 60), rnd.nextInt(-60, 60) to rnd.nextInt(-60, 60)) })
            val x = rnd.nextDouble(-60.0, 60.0)
            val y = rnd.nextDouble(-60.0, 60.0)
            val hit = probe(scene, x, y, tol = 15.0).hit ?: return@repeat
            assertEquals(hypot(hit.x - x, hit.y - y), hit.distance, GEOMETRY_EPS)
            assertTrue(hit.distance <= 15.0 + GEOMETRY_EPS)
        }
    }

    @Test
    fun hiddenLayers_and_ExcludedElements_should_NotBeTargets() {
        val hiddenLayer = LayerId("hidden")
        val hidden = LineElement(ElementBase(elId("h"), layerId = hiddenLayer), listOf(sp(0, 0), sp(50, 0)))
        val scene = sceneOfElements(hidden, line("v", 100 to 100, 150 to 100)).let { it.withLayers(it.layers + Layer(hiddenLayer, "Hidden", visible = false)) }
        assertNull(probe(scene, 1.0, 1.0).hit)
        val index = SpatialIndex().also { it.rebuild(scene) }
        assertNull(PointSnap.probe(scene, index, 101.0, 100.0, tolerance, PointSnapKinds.All, exclude = setOf(ElementId("v"))).hit)
    }

    @Test
    fun badInputs_should_ReturnNoHit() {
        val scene = sceneOfElements(line("a", 0 to 0, 10 to 0))
        assertNull(probe(scene, Double.NaN, 0.0).hit)
        assertNull(probe(scene, 0.0, 0.0, tol = 0.0).hit)
        assertNull(probe(scene, 0.0, 0.0, tol = Double.POSITIVE_INFINITY).hit)
    }

    @Test
    fun work_should_StayBounded_When_TheSceneHas500Elements() {
        val spread = sceneOfElements(*Array(500) { i -> line("s$i", (i % 25) * 100 to (i / 25) * 100, (i % 25) * 100 + 60 to (i / 25) * 100 + 40) })
        val sparse = probe(spread, 1230.0, 410.0)
        assertTrue(sparse.elements <= SPARSE_ELEMENTS, "a pointer in a 500-element scene read ${sparse.elements} elements")
        assertTrue(sparse.pairs <= SPARSE_ELEMENTS * SPARSE_ELEMENTS)

        val pileUp = sceneOfElements(*Array(500) { i -> line("p$i", -100 + i % 7 to -100 + i % 11, 100 + i % 13 to 100 - i % 5) })
        val dense = probe(pileUp, 0.0, 0.0, tol = 12.0)
        val budget = PointSnap.SEGMENT_BUDGET
        assertTrue(dense.pairs <= budget * (budget - 1) / 2, "intersection pairs ${dense.pairs} exceed the segment budget squared")
    }
}

private const val SEED = 20260424
private const val ITERATIONS = 400
private const val GEOMETRY_EPS = 1e-6
private const val SPARSE_ELEMENTS = 6
