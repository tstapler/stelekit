package dev.stapler.stelekit.sketch

import arrow.core.Either
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.Rational
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapEnginePropertyTest {
    private fun <A> Either<*, A>.ok(): A = fold({ error("unexpected $it") }, { it })
    private fun pt(x: Long, y: Long) = SketchPoint(Inches(x), Inches(y))
    private val tol = Rational(1, 8) // 12dp at zoom 1
    private val tolSixteenths = 2L

    private fun scene(vararg prims: SketchPrimitive): SketchScene =
        prims.fold(SketchScene.EMPTY) { s, p -> s.add(p).ok() }

    private val coord: Arb<Long> = Arb.long(-5_000L..5_000L)
    private val pointArb: Arb<SketchPoint> = arbitrary { rs -> pt(coord.sample(rs).value, coord.sample(rs).value) }
    private val lineArb: Arb<SketchPrimitive.Line> = arbitrary { rs ->
        SketchPrimitive.Line("l${rs.random.nextInt()}", pointArb.sample(rs).value, pointArb.sample(rs).value)
    }

    @Test
    fun endpointBeatsEveryLowerPriorityKind() = runTest {
        checkAll(lineArb, Arb.long(-1L..1L), Arb.long(-1L..1L)) { line, jx, jy ->
            val s = scene(line)
            val cursor = pt(line.a.x.sixteenths + jx, line.a.y.sixteenths + jy)
            val r = SnapEngine.snap(s, cursor, tol, Inches(1), axisAnchor = line.a).ok()
            assertEquals(SnapKind.ENDPOINT, r.kind)
            assertTrue(r.point == line.a || r.point == line.b)
        }
    }

    @Test
    fun midpointBeatsIntersectionGridAndAxis() {
        val s = scene(
            SketchPrimitive.Line("h", pt(0, 0), pt(160, 0)),
            SketchPrimitive.Line("v", pt(80, -80), pt(80, 80)),
        )
        // Midpoint of h is (80,0) which is also where h and v cross: midpoint outranks intersection.
        val r = SnapEngine.snap(s, pt(81, 1), tol, Inches(8), axisAnchor = pt(0, 0)).ok()
        assertEquals(SnapKind.MIDPOINT, r.kind)
        assertEquals(pt(80, 0), r.point)
    }

    @Test
    fun intersectionBeatsGridAndAxis() {
        val s = scene(
            SketchPrimitive.Line("a", pt(0, 0), pt(200, 100)),
            SketchPrimitive.Line("b", pt(0, 100), pt(300, 0)),
        )
        val r = SnapEngine.snap(s, pt(121, 61), tol, Inches(7), axisAnchor = pt(0, 0)).ok()
        assertEquals(SnapKind.INTERSECTION, r.kind)
        assertEquals(pt(120, 60), r.point)
    }

    @Test
    fun gridBeatsAxisLock() {
        val r = SnapEngine.snap(SketchScene.EMPTY, pt(33, 1), tol, Inches(16), axisAnchor = pt(0, 0)).ok()
        assertEquals(SnapKind.GRID, r.kind)
        assertEquals(pt(32, 0), r.point)
    }

    @Test
    fun gridResultIsAlwaysAMultipleOfTheStep() = runTest {
        checkAll(pointArb, Arb.long(1L..32L)) { cursor, step ->
            val r = SnapEngine.snap(SketchScene.EMPTY, cursor, Rational(40), Inches(step)).ok()
            assertEquals(SnapKind.GRID, r.kind)
            assertEquals(0L, r.point.x.sixteenths % step)
            assertEquals(0L, r.point.y.sixteenths % step)
            assertTrue(abs(r.point.x.sixteenths - cursor.x.sixteenths) * 2 <= step)
        }
    }

    @Test
    fun axisLockEngagesWithinThreeDegreesOnly() = runTest {
        val anchor = pt(0, 0)
        checkAll(Arb.long(200L..4_000L), Arb.long(-4_000L..4_000L)) { dx, dy ->
            val r = SnapEngine.snap(SketchScene.EMPTY, pt(dx, dy), tol, null, axisAnchor = anchor).ok()
            // tan(3 deg) is about 0.0524; stay clear of the boundary band.
            if (abs(dy) * 1000 <= dx * 52) {
                assertEquals(SnapKind.AXIS_LOCK, r.kind)
                assertEquals(pt(dx, 0), r.point)
            } else if (abs(dy) * 1000 >= dx * 53 && abs(dy) * 1000 <= dx * 19_000) {
                assertEquals(SnapKind.NONE, r.kind)
                assertEquals(pt(dx, dy), r.point)
            }
        }
    }

    @Test
    fun resultIsNeverFartherThanToleranceUnlessAxisLockOrNone() = runTest {
        checkAll(lineArb, lineArb, pointArb) { l1, l2, cursor ->
            val r = SnapEngine.snap(scene(l1, l2), cursor, tol, Inches(4)).ok()
            if (r.kind != SnapKind.NONE) {
                val d = maxOf(
                    abs(r.point.x.sixteenths - cursor.x.sixteenths),
                    abs(r.point.y.sixteenths - cursor.y.sixteenths),
                )
                assertTrue(d <= tolSixteenths + 1, "kind ${r.kind} moved $d sixteenths")
            }
        }
    }

    @Test
    fun snappingAnEndpointIsIdempotent() = runTest {
        checkAll(lineArb) { line ->
            val s = scene(line)
            val first = SnapEngine.snap(s, line.a, tol, null).ok()
            assertEquals(SnapKind.ENDPOINT, first.kind)
            assertEquals(first.point, SnapEngine.snap(s, first.point, tol, null).ok().point)
        }
    }

    @Test
    fun hitToleranceIsInclusiveAndExact() {
        val s = scene(SketchPrimitive.Line("h", pt(0, 0), pt(160, 0)))
        assertEquals("h", SnapEngine.hitTest(s, pt(80, 2), tol).ok().primitive?.id)
        assertNull(SnapEngine.hitTest(s, pt(80, 3), tol).ok().primitive)
        assertEquals("h", SnapEngine.hitTest(s, pt(-1, 1), tol).ok().primitive?.id)
    }

    @Test
    fun hitTestPicksNearestOutline() {
        val s = scene(
            SketchPrimitive.Rect("r", pt(0, 0), pt(160, 160)),
            SketchPrimitive.Line("l", pt(0, 1), pt(160, 1)),
        )
        assertEquals("l", SnapEngine.hitTest(s, pt(80, 1), tol).ok().primitive?.id)
    }

    @Test
    fun aabbPruneKeepsCandidateCountSmallOn200Primitives() {
        // 200 short lines on a 20 x 10 lattice, 100 sixteenths apart.
        var s = SketchScene.EMPTY
        for (i in 0 until 200) {
            val x = (i % 20) * 100L
            val y = (i / 20) * 100L
            s = s.add(SketchPrimitive.Line("p$i", pt(x, y), pt(x + 40, y + 10))).ok()
        }
        val snap = SnapEngine.snap(s, pt(1_001, 301), tol, Inches(16)).ok()
        assertEquals(SnapKind.ENDPOINT, snap.kind)
        assertEquals(pt(1_000, 300), snap.point)
        assertTrue(snap.candidateCount in 1..4, "snap candidates ${snap.candidateCount}")
        val hit = SnapEngine.hitTest(s, pt(1_020, 305), tol).ok()
        assertNotNull(hit.primitive)
        assertTrue(hit.candidateCount in 1..4, "hit candidates ${hit.candidateCount}")
    }

    @Test
    fun sceneEnforcesCapacityDuplicatesAndCoordinateRange() {
        val s = scene(SketchPrimitive.Line("a", pt(0, 0), pt(1, 1)))
        assertTrue(s.add(SketchPrimitive.Line("a", pt(0, 0), pt(2, 2))).isLeft())
        assertTrue(s.add(SketchPrimitive.Line("far", pt(0, 0), pt(SketchScene.MAX_COORD_SIXTEENTHS + 1, 0))).isLeft())
        var full = SketchScene.EMPTY
        for (i in 0 until SketchScene.MAX_PRIMITIVES) {
            full = full.add(SketchPrimitive.Line("p$i", pt(0, 0), pt(1, 1))).ok()
        }
        assertEquals(SketchScene.MAX_PRIMITIVES, full.size)
        assertEquals(SceneError.CapacityExceeded(SketchScene.MAX_PRIMITIVES), full.add(SketchPrimitive.Line("x", pt(0, 0), pt(1, 1))).swap().getOrNull())
        assertEquals(SketchScene.MAX_PRIMITIVES - 1, full.remove("p0").size)
    }

    @Test
    fun pointerSnapsThroughTheViewTransform() {
        val s = scene(SketchPrimitive.Line("h", pt(0, 0), pt(160, 0)))
        val view = ViewTransform.of(Rational(2), ScreenPoint(Rational(10), Rational(20)))
        // 192 dp per inch: sheet (160,0) sixteenths = 10 in = 1920 dp; pointer 3 dp off.
        val r = snapPointer(s, view, ScreenPoint(Rational(10 + 1920 + 3), Rational(20 - 2)), null).ok()
        assertEquals(SnapKind.ENDPOINT, r.kind)
        assertEquals(pt(160, 0), r.point)
        assertEquals("h", hitPointer(s, view, ScreenPoint(Rational(10 + 960), Rational(20 + 4))).ok().primitive?.id)
    }
}
