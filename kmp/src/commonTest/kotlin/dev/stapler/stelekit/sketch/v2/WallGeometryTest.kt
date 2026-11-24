// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningKind
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.WallCut
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.WallGeometry
import dev.stapler.stelekit.sketch.v2.scene.WallJustification
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
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

/** Walls: mitred joints, rooms, where an opening may sit, and what the double-line drawing covers. */
class WallGeometryTest {
    private fun u(d: Double) = SheetFixtures.u(d)

    private fun pt(x: Double, y: Double) = SheetFixtures.pt(x, y)

    private fun wall(points: List<ScenePoint>, thickness: Double = 8.0, justification: WallJustification = WallJustification.Centre, closed: Boolean = false) =
        WallElement(ElementBase(ElementId("w")), points, u(thickness), justification, closed)

    private fun opening(segment: Int, offset: Double, width: Double, kind: OpeningKind = OpeningKind.Door) =
        OpeningElement(ElementBase(ElementId("o")), ElementId("w"), segment, u(offset), u(width), kind)

    /** A walk of [count] vertices whose every turn is within a hundred degrees, so no joint is bevelled. */
    private fun walk(r: Random, count: Int): List<ScenePoint> {
        var x = 0.0
        var y = 0.0
        var heading = r.nextDouble() * 2 * PI
        val out = arrayListOf(pt(x, y))
        repeat(count - 1) {
            val length = 20.0 + r.nextDouble() * 100.0
            x += length * cos(heading)
            y += length * sin(heading)
            out += pt(x, y)
            heading += (r.nextDouble() - 0.5) * (200.0 * PI / 180.0)
        }
        return out
    }

    @Test
    fun mitreJoint_should_BeTheFaceOffsetFromBothAdjacentSegments_When_TheTurnIsNotSharp() = runTest {
        checkAll(PropTestConfig(iterations = 200), Arb.long()) { seed ->
            val r = Random(seed)
            val w = wall(walk(r, 3 + r.nextInt(4)), thickness = 2.0 + r.nextDouble() * 20.0)
            val segs = WallGeometry.segments(w)
            val offsets = WallGeometry.offsets(w)
            for ((edges, d) in listOf(WallGeometry.leftFace(w) to offsets.left, WallGeometry.rightFace(w) to -offsets.right)) {
                for (i in 0 until edges.lastIndex) {
                    val joint = edges[i].end
                    assertEquals(d, (joint - segs[i].a).dot(segs[i].n), 1e-6)
                    assertEquals(d, (joint - segs[i + 1].a).dot(segs[i + 1].n), 1e-6)
                    assertEquals(0.0, joint.distanceTo(edges[i + 1].start), 1e-9)
                }
            }
        }
    }

    @Test
    fun sharpHairpin_should_BeBevelledNotSpiked_When_TheWallDoublesBack() {
        val w = wall(listOf(pt(0.0, 0.0), pt(100.0, 0.0), pt(0.0, 4.0)), thickness = 8.0)
        for (face in listOf(WallGeometry.leftFace(w), WallGeometry.rightFace(w))) {
            for (e in face) assertTrue(hypot(e.end.x - e.start.x, e.end.y - e.start.y) < 400.0, "a spike ${e.end}")
        }
    }

    @Test
    fun closedRoom_should_HaveTheClearAreaOfItsJustification_ForBothWindings() {
        val w = 200.0
        val h = 120.0
        val t = 8.0
        val clockwise = listOf(pt(0.0, 0.0), pt(w, 0.0), pt(w, h), pt(0.0, h))
        for (corners in listOf(clockwise, clockwise.reversed())) {
            val inside = wall(corners, t, WallJustification.Inside, closed = true)
            val centre = wall(corners, t, WallJustification.Centre, closed = true)
            val outside = wall(corners, t, WallJustification.Outside, closed = true)
            assertEquals(w * h, assertNotNull(WallGeometry.interiorArea(inside)), 1e-6)
            assertEquals((w - t) * (h - t), assertNotNull(WallGeometry.interiorArea(centre)), 1e-6)
            assertEquals((w - 2 * t) * (h - 2 * t), assertNotNull(WallGeometry.interiorArea(outside)), 1e-6)
        }
    }

    @Test
    fun interiorArea_should_BeNull_When_TheWallIsOpen() {
        assertNull(WallGeometry.interiorArea(wall(listOf(pt(0.0, 0.0), pt(10.0, 0.0)))))
    }

    @Test
    fun polygonArea_should_MatchTheShoelaceOfAnyRotatedRectangle() = runTest {
        checkAll(PropTestConfig(iterations = 100), Arb.long()) { seed ->
            val r = Random(seed)
            val a = 10.0 + r.nextDouble() * 300.0
            val b = 10.0 + r.nextDouble() * 300.0
            val angle = r.nextDouble() * 2 * PI
            val corners = listOf(0.0 to 0.0, a to 0.0, a to b, 0.0 to b).map { (x, y) -> pt(x * cos(angle) - y * sin(angle), x * sin(angle) + y * cos(angle)) }
            val room = wall(corners, 6.0, WallJustification.Inside, closed = true)
            assertEquals(a * b, assertNotNull(WallGeometry.interiorArea(room)), 1e-3)
        }
    }

    @Test
    fun fit_should_KeepTheOpeningInsideItsSegment_ForAnyOffsetAndWidth() = runTest {
        checkAll(PropTestConfig(iterations = 300), Arb.long()) { seed ->
            val r = Random(seed)
            val w = wall(walk(r, 3 + r.nextInt(4)), thickness = 2.0 + r.nextDouble() * 12.0, closed = false)
            val segment = r.nextInt(w.segmentCount)
            val span = WallGeometry.span(w, segment) ?: return@checkAll
            val (offset, width) = assertNotNull(WallGeometry.fit(w, segment, (r.nextDouble() - 0.3) * 400.0, 1.0 + r.nextDouble() * 300.0))
            assertTrue(offset >= span.lo - 1e-9, "starts before the free span: $offset < ${span.lo}")
            assertTrue(offset + width <= span.hi + 1e-9, "ends after the free span: ${offset + width} > ${span.hi}")
            assertTrue(width > 0.0)
        }
    }

    @Test
    fun fit_should_BeIdempotent_And_LeaveAnOpeningThatAlreadyFitsAlone() {
        val w = wall(listOf(pt(0.0, 0.0), pt(300.0, 0.0), pt(300.0, 200.0)))
        val (o1, w1) = assertNotNull(WallGeometry.fit(w, 0, 20.0, 60.0))
        assertEquals(20.0 to 60.0, o1 to w1)
        val (o2, w2) = assertNotNull(WallGeometry.fit(w, 0, 290.0, 60.0))
        assertEquals(o2 to w2, assertNotNull(WallGeometry.fit(w, 0, o2, w2)))
    }

    @Test
    fun fit_should_ShrinkAnOpeningWiderThanTheSegment_And_RefuseAMissingSegment() {
        val w = wall(listOf(pt(0.0, 0.0), pt(50.0, 0.0)))
        val (offset, width) = assertNotNull(WallGeometry.fit(w, 0, 0.0, 500.0))
        assertEquals(0.0, offset, 1e-9)
        assertEquals(50.0, width, 1e-9)
        assertNull(WallGeometry.fit(w, 3, 0.0, 10.0))
    }

    @Test
    fun footprint_should_SitOnTheBodyOfTheWall_ForEveryJustification() {
        for (j in WallJustification.entries) {
            val w = wall(listOf(pt(0.0, 0.0), pt(300.0, 0.0)), thickness = 10.0, justification = j)
            val foot = assertNotNull(WallGeometry.footprint(w, opening(0, 100.0, 40.0)))
            val o = WallGeometry.offsets(w)
            assertEquals(120.0, foot.centre.x, 1e-9)
            assertEquals(-(o.left - o.right) / 2.0, foot.centre.y, 1e-9)
            assertEquals(10.0, foot.thickness, 1e-9)
            assertEquals(40.0, foot.width, 1e-9)
        }
    }

    @Test
    fun layout_should_FillTheWholeBody_And_RemoveExactlyTheCutOfADoor() {
        val corners = listOf(pt(0.0, 0.0), pt(200.0, 0.0), pt(200.0, 120.0), pt(0.0, 120.0))
        val room = wall(corners, 8.0, WallJustification.Centre, closed = true)
        val whole = WallGeometry.layout(room, emptyList())
        val bodyArea = (208.0 * 128.0) - (192.0 * 112.0)
        assertEquals(bodyArea, whole.fills.sumOf { WallGeometry.area(it) }, 1e-6)
        val cut = WallGeometry.layout(room, listOf(WallCut(0, 50.0, 86.0)))
        assertEquals(bodyArea - 36.0 * 8.0, cut.fills.sumOf { WallGeometry.area(it) }, 1e-6)
        assertEquals(1, whole.strokes.count { it.closed } / 2, "a closed room's two faces are two closed strokes")
        assertTrue(cut.strokes.none { it.closed }, "a cut face is an open run")
    }

    @Test
    fun layout_should_NotJoinAcrossASegmentThatAnOpeningCutsAwayCompletely() {
        val w = wall(listOf(pt(0.0, 0.0), pt(100.0, 0.0), pt(100.0, 200.0)))
        val span = assertNotNull(WallGeometry.span(w, 0))
        val layout = WallGeometry.layout(w, listOf(WallCut(0, span.lo, span.hi)))
        for (stroke in layout.strokes) {
            for (i in 0 until stroke.points.size - 1) {
                val a = stroke.points[i]
                val b = stroke.points[i + 1]
                assertTrue(abs(a.x - b.x) < 1e-9 || abs(a.y - b.y) < 1e-9, "a stroke runs diagonally across the removed segment: $a to $b")
            }
        }
    }

    @Test
    fun layout_should_DrawEndCapsOnAnOpenWallOnly() {
        val open = WallGeometry.layout(wall(listOf(pt(0.0, 0.0), pt(100.0, 0.0), pt(100.0, 60.0))), emptyList())
        assertEquals(2 + 2, open.strokes.size, "two faces and two end caps")
        val closed = WallGeometry.layout(wall(listOf(pt(0.0, 0.0), pt(100.0, 0.0), pt(100.0, 60.0)), closed = true), emptyList())
        assertEquals(2, closed.strokes.size, "two closed faces and no caps")
    }

    @Test
    fun doorSymbol_should_SwingAnArcOfTheDoorWidthAboutTheHinge() = runTest {
        checkAll(PropTestConfig(iterations = 100), Arb.long()) { seed ->
            val r = Random(seed)
            val w = wall(listOf(pt(0.0, 0.0), pt(400.0, 0.0)), thickness = 8.0)
            val door = opening(0, 20.0 + r.nextDouble() * 100.0, 20.0 + r.nextDouble() * 60.0).copy(flipSide = r.nextBoolean(), flipHinge = r.nextBoolean())
            val (leaf, arc) = WallGeometry.symbol(w, door)
            val hinge = leaf.points.first()
            for (p in arc.points) assertEquals(door.width.value, hinge.distanceTo(p), 1e-6)
            assertEquals(door.width.value, hinge.distanceTo(leaf.points.last()), 1e-6)
            val expectedSide = if (door.flipSide) 1.0 else -1.0
            assertTrue((leaf.points.last().y - hinge.y) * expectedSide > 0.0, "the leaf swings to the ${if (door.flipSide) "right" else "left"} of travel")
        }
    }

    @Test
    fun probe_should_FindTheNearestSegmentAndTheDistanceAlongIt() {
        val w = wall(listOf(pt(0.0, 0.0), pt(100.0, 0.0), pt(100.0, 100.0)))
        val p = WallGeometry.probe(w, 98.0, 40.0)
        assertEquals(1, p.segment)
        assertEquals(40.0, p.s, 1e-9)
        assertEquals(2.0, p.distance, 1e-9)
        assertTrue(abs(WallGeometry.length(w) - 200.0) < 1e-9)
    }
}
