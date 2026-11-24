// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.snap

import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * The features of the drawing a pointer can lock onto, in priority order: when several are within reach the earlier kind wins,
 * however far the later one is (an endpoint beats a midpoint 1 px closer), and within one kind the nearer one does.
 * [Perpendicular] and [Tangent] need an anchor (the other end of the segment being drawn).
 */
enum class PointSnapKind { Endpoint, Intersection, Midpoint, Centre, Perpendicular, Tangent }

/** The point kinds that are switched on; [None] is the default of every tool context, so a tool test sees raw geometry. */
data class PointSnapKinds(val enabled: Set<PointSnapKind> = emptySet()) {
    val any: Boolean get() = enabled.isNotEmpty()

    operator fun contains(kind: PointSnapKind): Boolean = kind in enabled

    fun with(kind: PointSnapKind, on: Boolean): PointSnapKinds = PointSnapKinds(if (on) enabled + kind else enabled - kind)

    companion object {
        val None = PointSnapKinds()
        val All = PointSnapKinds(PointSnapKind.entries.toSet())
    }
}

/** A locked-on point: where it is, which feature it is and how far the pointer was from it (scene units). */
data class PointSnapHit(val x: Double, val y: Double, val kind: PointSnapKind, val distance: Double)

/** [hit] is null when nothing was in reach; [elements] and [pairs] count the work done, so a test can bound it without a clock. */
data class PointSnapProbe(val hit: PointSnapHit?, val elements: Int, val pairs: Int)

/**
 * Pure geometry behind the endpoint, midpoint, intersection, perpendicular, tangent and centre snaps. Only elements whose
 * bounds touch the square of half-width `tolerance` around the pointer are read (through [SpatialIndex]), and intersections are
 * tried only among the [SEGMENT_BUDGET] segments nearest the pointer, so a move frame costs the same in a 5-element and a
 * 5000-element scene. An intersection within reach of the pointer lies on both of its segments, so both pass within reach too:
 * nothing is missed by looking only at nearby segments.
 */
object PointSnap {
    /** The most segments paired up for intersections in one probe: bounds the pair count at the square of this. */
    const val SEGMENT_BUDGET = 24

    /** Slack for parameters on a segment and for a circle's roundness, in the unit of the quantity compared. */
    private const val EPS = 1e-9

    private const val HALF = 2.0

    private const val POLYGON_MIN_VERTICES = 3

    internal class Seg(val ax: Double, val ay: Double, val bx: Double, val by: Double) {
        fun distanceTo(x: Double, y: Double): Double {
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0.0, 1.0)
            return hypot(x - (ax + t * dx), y - (ay + t * dy))
        }
    }

    internal class Circle(val cx: Double, val cy: Double, val r: Double)

    private class Shape {
        val vertices = ArrayList<DoubleArray>()
        val segments = ArrayList<Seg>()
        val centres = ArrayList<DoubleArray>()
        val circles = ArrayList<Circle>()
    }

    /**
     * The best snap for a pointer at ([x], [y]) within [tolerance] scene units, or none. [anchor] is the fixed end of the segment
     * being drawn (enables perpendicular and tangent); [exclude] elements are not targets (the one being edited) and
     * [extraVertices] are further endpoints to offer (the other vertices of that element).
     */
    @Suppress("LongParameterList")
    fun probe(
        scene: SketchSceneV2,
        index: SpatialIndex,
        x: Double,
        y: Double,
        tolerance: Double,
        kinds: PointSnapKinds,
        anchor: ScenePoint? = null,
        exclude: Set<ElementId> = emptySet(),
        extraVertices: List<ScenePoint> = emptyList(),
    ): PointSnapProbe {
        val usable = tolerance.isFinite() && tolerance > 0.0 && x.isFinite() && y.isFinite()
        if (!kinds.any || !usable) return PointSnapProbe(null, 0, 0)
        val hidden = scene.layers.filter { !it.visible }.mapTo(HashSet()) { it.id }
        val near = index.query(Bounds.around(x, y, tolerance)).filter { it !in exclude }.sortedBy { it.value }
            .mapNotNull { scene[it] }.filter { it.base.layerId !in hidden }
        val shapes = near.map(::shapeOf)
        val found = ArrayList<PointSnapHit>()
        fun offer(kind: PointSnapKind, px: Double, py: Double) {
            val d = hypot(px - x, py - y)
            if (kind in kinds && d <= tolerance) found += PointSnapHit(px, py, kind, d)
        }
        for (v in extraVertices) offer(PointSnapKind.Endpoint, v.x.value, v.y.value)
        for (s in shapes) {
            for (v in s.vertices) offer(PointSnapKind.Endpoint, v[0], v[1])
            for (c in s.centres) offer(PointSnapKind.Centre, c[0], c[1])
            for (g in s.segments) offer(PointSnapKind.Midpoint, (g.ax + g.bx) / HALF, (g.ay + g.by) / HALF)
        }
        val reachable = shapes.flatMap { it.segments }.filter { it.distanceTo(x, y) <= tolerance }
            .sortedWith(compareBy<Seg> { it.distanceTo(x, y) }.thenBy { it.ax }.thenBy { it.ay }.thenBy { it.bx }.thenBy { it.by })
            .take(SEGMENT_BUDGET)
        var pairs = 0
        if (PointSnapKind.Intersection in kinds) {
            for (i in reachable.indices) for (j in i + 1 until reachable.size) {
                pairs++
                intersection(reachable[i], reachable[j])?.let { offer(PointSnapKind.Intersection, it.first, it.second) }
            }
        }
        if (anchor != null) {
            val ax = anchor.x.value
            val ay = anchor.y.value
            if (PointSnapKind.Perpendicular in kinds) reachable.forEach { g -> foot(ax, ay, g)?.let { offer(PointSnapKind.Perpendicular, it.first, it.second) } }
            if (PointSnapKind.Tangent in kinds) shapes.flatMap { it.circles }.forEach { c -> tangentPoints(ax, ay, c).forEach { offer(PointSnapKind.Tangent, it.first, it.second) } }
        }
        val best = found.minWithOrNull(compareBy<PointSnapHit> { it.kind.ordinal }.thenBy { it.distance }.thenBy { it.x }.thenBy { it.y })
        return PointSnapProbe(best, near.size, pairs)
    }

    /** The crossing of the two segments, or null when they are parallel or miss each other. */
    internal fun intersection(p: Seg, q: Seg): Pair<Double, Double>? {
        val rx = p.bx - p.ax
        val ry = p.by - p.ay
        val sx = q.bx - q.ax
        val sy = q.by - q.ay
        val denom = rx * sy - ry * sx
        if (abs(denom) <= EPS * max(1.0, hypot(rx, ry) * hypot(sx, sy))) return null
        val t = ((q.ax - p.ax) * sy - (q.ay - p.ay) * sx) / denom
        val u = ((q.ax - p.ax) * ry - (q.ay - p.ay) * rx) / denom
        if (!onSegment(t) || !onSegment(u)) return null
        return (p.ax + t * rx) to (p.ay + t * ry)
    }

    private fun onSegment(param: Double): Boolean = param >= -EPS && param <= 1.0 + EPS

    /** The foot of the perpendicular from ([ax], [ay]) onto [g], or null when it falls outside the segment or on the anchor. */
    internal fun foot(ax: Double, ay: Double, g: Seg): Pair<Double, Double>? {
        val dx = g.bx - g.ax
        val dy = g.by - g.ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return null
        val t = ((ax - g.ax) * dx + (ay - g.ay) * dy) / len2
        if (!onSegment(t)) return null
        val fx = g.ax + t * dx
        val fy = g.ay + t * dy
        return if (hypot(fx - ax, fy - ay) <= EPS) null else fx to fy
    }

    /** The two points where a line from ([ax], [ay]) touches [c]; none when the anchor is on or inside the circle. */
    internal fun tangentPoints(ax: Double, ay: Double, c: Circle): List<Pair<Double, Double>> {
        val d = hypot(ax - c.cx, ay - c.cy)
        if (d <= c.r * (1.0 + EPS)) return emptyList()
        val base = atan2(ay - c.cy, ax - c.cx)
        val spread = acos(c.r / d)
        return listOf(base + spread, base - spread).map { a -> (c.cx + c.r * cos(a)) to (c.cy + c.r * sin(a)) }
    }

    private fun shapeOf(e: SceneElement): Shape {
        val s = Shape()
        when (e) {
            is LineElement -> polyline(s, e.points, e.polygon)
            is ArrowElement -> polyline(s, e.points, false)
            is PointElement -> s.vertices += doubleArrayOf(e.x.value, e.y.value)
            is RectElement -> polygon(s, boxCorners(e.x.value, e.y.value, e.width.value, e.height.value, e.angle))
            is DiamondElement -> polygon(s, diamondCorners(e.x.value, e.y.value, e.width.value, e.height.value, e.angle))
            is EllipseElement -> {
                s.centres += doubleArrayOf(e.cx.value, e.cy.value)
                if (abs(e.rx.value - e.ry.value) <= EPS * max(1.0, e.rx.value)) s.circles += Circle(e.cx.value, e.cy.value, e.rx.value)
            }
            is ArcElement -> s.centres += doubleArrayOf(e.cx.value, e.cy.value)
            else -> Unit
        }
        return s
    }

    private fun polyline(s: Shape, points: List<ScenePoint>, closed: Boolean) {
        points.forEach { s.vertices += doubleArrayOf(it.x.value, it.y.value) }
        for (i in 0 until points.size - 1) s.segments += Seg(points[i].x.value, points[i].y.value, points[i + 1].x.value, points[i + 1].y.value)
        if (closed && points.size >= POLYGON_MIN_VERTICES) s.segments += Seg(points.last().x.value, points.last().y.value, points.first().x.value, points.first().y.value)
    }

    private fun polygon(s: Shape, corners: List<Pair<Double, Double>>) {
        corners.forEach { s.vertices += doubleArrayOf(it.first, it.second) }
        corners.indices.forEach { i ->
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            s.segments += Seg(a.first, a.second, b.first, b.second)
        }
    }

    private fun boxCorners(x: Double, y: Double, w: Double, h: Double, angle: Double) =
        turned(listOf(x to y, x + w to y, x + w to y + h, x to y + h), x + w / HALF, y + h / HALF, angle)

    private fun diamondCorners(x: Double, y: Double, w: Double, h: Double, angle: Double) =
        turned(listOf(x + w / HALF to y, x + w to y + h / HALF, x + w / HALF to y + h, x to y + h / HALF), x + w / HALF, y + h / HALF, angle)

    private fun turned(points: List<Pair<Double, Double>>, cx: Double, cy: Double, angle: Double): List<Pair<Double, Double>> {
        if (angle == 0.0) return points
        val c = cos(angle)
        val s = sin(angle)
        return points.map { (px, py) -> (cx + (px - cx) * c - (py - cy) * s) to (cy + (px - cx) * s + (py - cy) * c) }
    }
}
