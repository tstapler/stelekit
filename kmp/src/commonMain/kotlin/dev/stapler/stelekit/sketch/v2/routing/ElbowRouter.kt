// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.routing

import kotlin.math.abs

/** An axis-aligned rectangle a route must not enter, in scene units. */
data class RouteRect(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    fun inflated(d: Double): RouteRect = RouteRect(minX - d, minY - d, maxX + d, maxY + d)

    /** True when the open segment (a to b, axis-aligned or not) passes through this rectangle's interior. */
    fun interiorCrossedBy(ax: Double, ay: Double, bx: Double, by: Double): Boolean {
        val lo = minOf(ax, bx)
        val hi = maxOf(ax, bx)
        val loY = minOf(ay, by)
        val hiY = maxOf(ay, by)
        return lo < maxX - EPS && hi > minX + EPS && loY < maxY - EPS && hiY > minY + EPS
    }

    private companion object {
        const val EPS = 1e-9
    }
}

/** A point of a route. */
data class RoutePoint(val x: Double, val y: Double)

/**
 * One end of a connector: where it touches its shape ([x], [y]), the axis direction it leaves the shape along ([dx], [dy], one of
 * the four unit steps, or (0, 0) for a free end), the [obstacle] it must stay out of (the bound shape's box) and how far it runs
 * out before it may turn ([stub], at least the margin).
 */
data class RouteEnd(val x: Double, val y: Double, val dx: Int = 0, val dy: Int = 0, val obstacle: RouteRect? = null, val stub: Double = ElbowRouter.MARGIN) {
    val free: Boolean get() = dx == 0 && dy == 0
}

/**
 * Orthogonal (elbow) routing for connectors, a pure function of its input so the same scene always gives the same route.
 *
 * The route leaves each bound end along its side's normal for [MARGIN] units, then runs on the Hanan grid of the stub points and the
 * boxes' clearance lines, found by a shortest-path search whose cost is (bends, length) in that order. So the result has the fewest
 * bends any grid route has and, among those, the shortest, and it never enters either bound shape's box (nor its clearance band).
 * [mid] is the preferred coordinate of the middle segment of a two-bend route (x for a vertical middle, y for a horizontal one): the
 * handle the user drags; it is honoured when the segment there stays clear. When the ends sit inside each other's clearance (the
 * shapes overlap) no clear route exists and a plain two-bend route ignoring the boxes is returned.
 */
object ElbowRouter {
    /** How far a route leaves a shape before it may turn: one inch. */
    const val MARGIN = 16.0

    private const val HALF = 2.0
    private const val EPS = 1e-9
    private val DIRS = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)
    private const val NONE = 4

    fun route(start: RouteEnd, end: RouteEnd, mid: Double? = null): List<RoutePoint> {
        val inflated = listOfNotNull(start.obstacle, end.obstacle).map { it.inflated(MARGIN / HALF) }
        val s1 = stub(start)
        val e1 = stub(end)
        val found = search(s1, e1, start, end, inflated, mid) ?: fallback(s1, e1, start)
        val full = compress(listOf(RoutePoint(start.x, start.y)) + found + RoutePoint(end.x, end.y))
        return preferMid(full, mid, listOfNotNull(start.obstacle, end.obstacle))
    }

    /** The number of turns of [points]. */
    fun bends(points: List<RoutePoint>): Int = maxOf(0, points.size - 2)

    private fun stub(e: RouteEnd) = RoutePoint(e.x + e.dx * e.stub, e.y + e.dy * e.stub)

    private fun lines(values: List<Double>): DoubleArray {
        val sorted = values.sorted()
        val out = ArrayList<Double>()
        for (v in sorted) if (out.isEmpty() || abs(v - out.last()) > EPS) out += v
        return out.toDoubleArray()
    }

    private class Cost(val bends: Int, val length: Double) {
        fun lessThan(o: Cost): Boolean = bends < o.bends || (bends == o.bends && length < o.length - EPS)
    }

    @Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun search(s1: RoutePoint, e1: RoutePoint, start: RouteEnd, end: RouteEnd, obstacles: List<RouteRect>, mid: Double?): List<RoutePoint>? {
        val xs = lines(listOf(s1.x, e1.x, (s1.x + e1.x) / HALF) + obstacles.flatMap { listOf(it.minX, it.maxX) } + listOfNotNull(mid))
        val ys = lines(listOf(s1.y, e1.y, (s1.y + e1.y) / HALF) + obstacles.flatMap { listOf(it.minY, it.maxY) } + listOfNotNull(mid))
        fun at(v: DoubleArray, value: Double) = v.indexOfFirst { abs(it - value) <= EPS }
        val sx = at(xs, s1.x)
        val sy = at(ys, s1.y)
        val ex = at(xs, e1.x)
        val ey = at(ys, e1.y)
        val nx = xs.size
        val ny = ys.size
        fun id(i: Int, j: Int, d: Int) = (j * nx + i) * (NONE + 1) + d
        val total = nx * ny * (NONE + 1)
        val best = arrayOfNulls<Cost>(total)
        val from = IntArray(total) { -1 }
        val done = BooleanArray(total)
        val first = if (start.free) NONE else DIRS.indexOf(start.dx to start.dy)
        best[id(sx, sy, first)] = Cost(0, 0.0)
        val wanted = if (end.free) NONE else DIRS.indexOf(-end.dx to -end.dy)
        var goal = -1
        var goalCost: Cost? = null
        while (true) {
            var u = -1
            for (k in 0 until total) {
                val c = best[k] ?: continue
                if (!done[k] && (u < 0 || c.lessThan(best[u]!!))) u = k
            }
            if (u < 0) break
            done[u] = true
            val d = u % (NONE + 1)
            val cell = u / (NONE + 1)
            val i = cell % nx
            val j = cell / nx
            val cu = best[u]!!
            if (i == ex && j == ey) {
                val extra = if (wanted == NONE || d == NONE || d == wanted) 0 else 1
                val total2 = Cost(cu.bends + extra, cu.length)
                if (goalCost == null || total2.lessThan(goalCost)) {
                    goalCost = total2
                    goal = u
                }
            }
            for (nd in DIRS.indices) {
                if (d != NONE && (nd + 2) % NONE == d) continue
                val ni = i + DIRS[nd].first
                val nj = j + DIRS[nd].second
                if (ni !in 0 until nx || nj !in 0 until ny) continue
                if (obstacles.any { it.interiorCrossedBy(xs[i], ys[j], xs[ni], ys[nj]) }) continue
                val turn = if (d == NONE || d == nd) 0 else 1
                val c = Cost(cu.bends + turn, cu.length + abs(xs[ni] - xs[i]) + abs(ys[nj] - ys[j]))
                val v = id(ni, nj, nd)
                if (!done[v] && (best[v] == null || c.lessThan(best[v]!!))) {
                    best[v] = c
                    from[v] = u
                }
            }
        }
        if (goal < 0) return null
        val path = ArrayList<RoutePoint>()
        var k = goal
        while (k >= 0) {
            val cell = k / (NONE + 1)
            path += RoutePoint(xs[cell % nx], ys[cell / nx])
            k = from[k]
        }
        return path.reversed()
    }

    /** A two-bend route whose middle segment is perpendicular to the run is moved to [mid] when that stays clear. */
    private fun preferMid(path: List<RoutePoint>, mid: Double?, obstacles: List<RouteRect>): List<RoutePoint> {
        val pts = path
        if (mid == null || pts.size != Z_POINTS) return pts
        val vertical = abs(pts[1].x - pts[2].x) <= EPS
        val moved = if (vertical) listOf(pts[0], RoutePoint(mid, pts[1].y), RoutePoint(mid, pts[2].y), pts[3]) else listOf(pts[0], RoutePoint(pts[1].x, mid), RoutePoint(pts[2].x, mid), pts[3])
        val clear = moved.zipWithNext().none { (a, b) -> obstacles.any { it.interiorCrossedBy(a.x, a.y, b.x, b.y) } }
        return if (clear) moved else pts
    }

    private const val Z_POINTS = 4
    private const val MIN_RUN = 2

    /** With no clear grid route: leave along the stubs and meet through one corner or a mid-line, ignoring the boxes. */
    private fun fallback(s1: RoutePoint, e1: RoutePoint, start: RouteEnd): List<RoutePoint> {
        val horizontalFirst = start.free || start.dx != 0
        val corner = if (horizontalFirst) RoutePoint(e1.x, s1.y) else RoutePoint(s1.x, e1.y)
        return listOf(s1, corner, e1)
    }

    /** [points] without repeated points and without a middle point on a straight run. */
    fun compress(points: List<RoutePoint>): List<RoutePoint> {
        val out = ArrayList<RoutePoint>()
        for (p in points) {
            if (out.isNotEmpty() && abs(out.last().x - p.x) <= EPS && abs(out.last().y - p.y) <= EPS) continue
            while (out.size >= MIN_RUN && collinear(out[out.size - MIN_RUN], out.last(), p)) out.removeAt(out.size - 1)
            out += p
        }
        return out
    }

    private fun collinear(a: RoutePoint, b: RoutePoint, c: RoutePoint): Boolean =
        (abs(a.x - b.x) <= EPS && abs(b.x - c.x) <= EPS && sameSide(a.y, b.y, c.y)) || (abs(a.y - b.y) <= EPS && abs(b.y - c.y) <= EPS && sameSide(a.x, b.x, c.x))

    /** True when b lies between a and c, so b is not a turn-back point (a reversal is kept, not merged). */
    private fun sameSide(a: Double, b: Double, c: Double): Boolean = (b - a) * (c - b) >= 0.0
}
