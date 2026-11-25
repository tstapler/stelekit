// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.routing

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** E11: the elbow router is orthogonal, deterministic, clear of both shapes, and uses the fewest turns. */
class ElbowRouterTest {
    private val eps = 1e-9

    private val a = RouteRect(0.0, 0.0, 100.0, 60.0)

    private fun rightOf(r: RouteRect, y: Double = (r.minY + r.maxY) / 2) = RouteEnd(r.maxX, y, 1, 0, r)
    private fun leftOf(r: RouteRect, y: Double = (r.minY + r.maxY) / 2) = RouteEnd(r.minX, y, -1, 0, r)
    private fun topOf(r: RouteRect, x: Double = (r.minX + r.maxX) / 2) = RouteEnd(x, r.minY, 0, -1, r)
    private fun bottomOf(r: RouteRect, x: Double = (r.minX + r.maxX) / 2) = RouteEnd(x, r.maxY, 0, 1, r)

    private fun orthogonal(route: List<RoutePoint>) =
        route.zipWithNext().all { (p, q) -> abs(p.x - q.x) <= eps || abs(p.y - q.y) <= eps }

    @Test
    fun facingShapesAtTheSameHeight_should_JoinStraight() {
        val b = RouteRect(300.0, 0.0, 400.0, 60.0)
        val route = ElbowRouter.route(rightOf(a), leftOf(b))
        assertEquals(0, ElbowRouter.bends(route))
        assertEquals(listOf(RoutePoint(100.0, 30.0), RoutePoint(300.0, 30.0)), route)
    }

    @Test
    fun facingShapesOffsetVertically_should_UseTwoBends() {
        val b = RouteRect(300.0, 200.0, 400.0, 260.0)
        val route = ElbowRouter.route(rightOf(a), leftOf(b))
        assertEquals(2, ElbowRouter.bends(route))
        assertTrue(orthogonal(route))
    }

    @Test
    fun rightSideToTopSideBelow_should_UseOneBend() {
        val b = RouteRect(300.0, 200.0, 400.0, 260.0)
        val route = ElbowRouter.route(rightOf(a), topOf(b))
        assertEquals(1, ElbowRouter.bends(route))
        assertEquals(RoutePoint(350.0, 30.0), route[1])
    }

    @Test
    fun rightSideToTheLeftSideOfAShapeBehind_should_GoAroundIt() {
        val b = RouteRect(-300.0, 0.0, -200.0, 60.0)
        val route = ElbowRouter.route(rightOf(a), leftOf(b))
        assertTrue(orthogonal(route))
        assertTrue(ElbowRouter.bends(route) >= 4)
        assertNoCrossing(route, a, b)
    }

    @Test
    fun middleSegment_should_MoveToThePreferredCoordinate_WhenItStaysClear() {
        val b = RouteRect(300.0, 200.0, 400.0, 260.0)
        val route = ElbowRouter.route(rightOf(a), leftOf(b), mid = 220.0)
        assertEquals(2, ElbowRouter.bends(route))
        assertEquals(220.0, route[1].x, eps)
        assertEquals(220.0, route[2].x, eps)
        val blocked = ElbowRouter.route(rightOf(a), leftOf(b), mid = 350.0)
        assertNoCrossing(blocked, a, b)
    }

    @Test
    fun aFreeEnd_should_RouteFromABoundSideWithoutAStub() {
        val route = ElbowRouter.route(rightOf(a), RouteEnd(400.0, 300.0))
        assertTrue(orthogonal(route))
        assertEquals(RoutePoint(400.0, 300.0), route.last())
        assertEquals(1, ElbowRouter.bends(route))
    }

    @Test
    fun overlappingShapes_should_StillGiveAnOrthogonalRoute() {
        val b = RouteRect(50.0, 20.0, 160.0, 90.0)
        val route = ElbowRouter.route(rightOf(a), leftOf(b))
        assertTrue(orthogonal(route))
        assertEquals(RoutePoint(100.0, 30.0), route.first())
    }

    private fun assertNoCrossing(route: List<RoutePoint>, vararg shapes: RouteRect) {
        for ((p, q) in route.zipWithNext()) {
            for (s in shapes) assertTrue(!s.interiorCrossedBy(p.x, p.y, q.x, q.y), "segment $p -> $q crosses $s")
        }
    }

    private fun end(side: Int, r: RouteRect) = when (side) {
        0 -> rightOf(r)
        1 -> leftOf(r)
        2 -> topOf(r)
        else -> bottomOf(r)
    }

    private fun shapes(ox: Int, oy: Int, w: Int, h: Int, ws: Int, hs: Int): Pair<RouteRect, RouteRect> {
        val first = RouteRect(0.0, 0.0, w.toDouble(), h.toDouble())
        // The second shape is kept clear of the first by at least two margins in x or y.
        val gap = 3 * ElbowRouter.MARGIN
        val bx = if (ox >= 0) w + gap + ox else -gap - ws + ox
        val by = oy.toDouble()
        return first to RouteRect(bx.toDouble(), by, bx.toDouble() + ws, by + hs)
    }

    private val sizes = Arb.int(20..200)
    private val offsets = Arb.int(-400..400)
    private val sidePick = Arb.int(0..3)

    @Test
    fun route_should_BeOrthogonalClearOfBothShapes_Deterministic_AndNeverMoreThanSixTurns() = runTest {
        checkAll(offsets, offsets, sizes, sizes, sizes, sizes, sidePick, sidePick) { ox, oy, w, h, ws, hs, s1, s2 ->
            val (p, q) = shapes(ox, oy, w, h, ws, hs)
            val route = ElbowRouter.route(end(s1, p), end(s2, q))
            assertTrue(orthogonal(route), "orthogonal $route")
            assertNoCrossing(route, p, q)
            assertEquals(route, ElbowRouter.route(end(s1, p), end(s2, q)))
            assertEquals(end(s1, p).x, route.first().x, eps)
            assertEquals(end(s2, q).y, route.last().y, eps)
            assertTrue(ElbowRouter.bends(route) <= MAX_BENDS, "bends ${ElbowRouter.bends(route)} for $route")
        }
    }

    @Test
    fun route_should_HaveNoMoreTurnsThanTheSimpleLOrZShapes_WhenSidesFaceEachOther() = runTest {
        checkAll(Arb.int(0..400), offsets, sizes, sizes, sizes, sizes) { ox, oy, w, h, ws, hs ->
            val (p, q) = shapes(ox, oy, w, h, ws, hs)
            val route = ElbowRouter.route(rightOf(p), leftOf(q))
            // Right side to the left side of a shape further right: a straight line or a Z.
            assertTrue(ElbowRouter.bends(route) <= 2, "bends for facing sides: $route")
        }
    }

    @Test
    fun route_should_StayStable_WhenTheOtherShapeMovesALittle() = runTest {
        checkAll(offsets, offsets, sizes, sizes, sizes, sizes, sidePick, sidePick) { ox, oy, w, h, ws, hs, s1, s2 ->
            val (p, q) = shapes(ox, oy, w, h, ws, hs)
            val moved = RouteRect(q.minX, q.minY + SMALL_MOVE, q.maxX, q.maxY + SMALL_MOVE)
            val before = ElbowRouter.route(end(s1, p), end(s2, q))
            val after = ElbowRouter.route(end(s1, p), end(s2, moved))
            assertTrue(abs(ElbowRouter.bends(before) - ElbowRouter.bends(after)) <= 2, "$before vs $after")
            assertTrue(abs(length(before) - length(after)) <= LENGTH_SLACK * SMALL_MOVE, "length jumped: ${length(before)} to ${length(after)}")
        }
    }

    private fun length(route: List<RoutePoint>) = route.zipWithNext().sumOf { (p, q) -> abs(p.x - q.x) + abs(p.y - q.y) }

    private companion object {
        const val MAX_BENDS = 6
        const val SMALL_MOVE = 0.5
        const val LENGTH_SLACK = 8.0
    }
}
