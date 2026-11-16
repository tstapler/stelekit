// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.hit

import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Narrow-phase hit testing over one immutable scene and its [SpatialIndex] (Story 1.3.3b). The tolerance is
 * [HIT_TOLERANCE_PX] screen pixels divided by the zoom (ADR-002), so it is 6 units at zoom 1 and 60 at zoom 0.1.
 *
 * What counts as a hit, per kind: a filled shape hits inside its outline or near it, an unfilled one only near its
 * outline; a line, arrow or stroke hits near any segment; a text or image hits over its box; a frame hits only near
 * its border so the elements inside stay reachable; a point hits within the tolerance disc; an arc hits near the arc
 * itself, not the rest of its circle. Elements on a hidden or locked layer, and locked elements, are skipped.
 * The topmost hit (layer order, then z-key, then array order) wins.
 */
class HitTester(private val scene: SketchSceneV2, private val index: SpatialIndex) {
    private val arrayIndex: Map<ElementId, Int> by lazy { scene.order.withIndex().associate { it.value to it.index } }

    /** The topmost element hit at [point], or null. */
    fun hit(point: ScenePoint, zoom: Double): ElementId? = hitAll(point, zoom).firstOrNull()

    /** Every element hit at [point], topmost first; an unusable zoom hits nothing. */
    fun hitAll(point: ScenePoint, zoom: Double): List<ElementId> = hitWhere(point, zoom, locked = false)

    /** The visible elements under [point] that [hitAll] skips because they, or their layer, are locked; topmost first (UX-101 counts them). */
    fun hitLocked(point: ScenePoint, zoom: Double): List<ElementId> = hitWhere(point, zoom, locked = true)

    private fun hitWhere(point: ScenePoint, zoom: Double, locked: Boolean): List<ElementId> {
        if (!zoom.isFinite() || zoom <= 0.0) return emptyList()
        val tolerance = HIT_TOLERANCE_PX / zoom
        val px = point.x.value
        val py = point.y.value
        val layers = scene.layers.associateBy { it.id }
        return index.query(Bounds.around(px, py, tolerance))
            .mapNotNull { scene[it] }
            .filter { e -> isVisible(e, layers) && isLocked(e, layers) == locked && hits(e, px, py, tolerance) }
            .sortedWith(compareByDescending<SceneElement> { (layers[it.base.layerId] ?: Layer.DefaultLayer).order }
                .thenByDescending { it.base.zKey }
                .thenByDescending { arrayIndex.getValue(it.id) })
            .map { it.id }
    }

    private fun isVisible(e: SceneElement, layers: Map<LayerId, Layer>): Boolean = (layers[e.base.layerId] ?: Layer.DefaultLayer).visible

    private fun isLocked(e: SceneElement, layers: Map<LayerId, Layer>): Boolean =
        (layers[e.base.layerId] ?: Layer.DefaultLayer).locked || e.base.locked

    private fun hits(e: SceneElement, px: Double, py: Double, tol: Double): Boolean = when (e) {
        is RectElement -> boxHit(e.x.value, e.y.value, e.width.value, e.height.value, e.angle, px, py, tol, filled(e))
        is TextElement -> boxHit(e.x.value, e.y.value, e.width.value, e.height.value, e.angle, px, py, tol, true)
        is ImageElement -> boxHit(e.x.value, e.y.value, e.width.value, e.height.value, e.angle, px, py, tol, true)
        is FrameElement -> boxHit(e.x.value, e.y.value, e.width.value, e.height.value, e.angle, px, py, tol, false)
        is DiamondElement -> diamondHit(e, px, py, tol)
        is EllipseElement -> ellipseHit(e, px, py, tol)
        is LineElement -> polylineHit(e.points, e.polygon, e.polygon && filled(e), px, py, tol)
        is ArrowElement -> polylineHit(e.points, false, false, px, py, tol)
        is FreedrawElement -> polylineHit(e.points, false, false, px, py, tol)
        is PointElement -> hypot(px - e.x.value, py - e.y.value) <= tol
        is ArcElement -> arcDistance(e, px, py) <= tol
    }

    private fun filled(e: SceneElement): Boolean = e.base.style.backgroundColor.let { it.isNotEmpty() && it != TRANSPARENT }

    /** [px],[py] expressed in the frame of a shape rotated by [angle] about ([cx],[cy]). */
    private fun local(px: Double, py: Double, cx: Double, cy: Double, angle: Double): Pair<Double, Double> {
        if (angle == 0.0) return px to py
        val c = cos(-angle)
        val s = sin(-angle)
        return (cx + (px - cx) * c - (py - cy) * s) to (cy + (px - cx) * s + (py - cy) * c)
    }

    private fun boxHit(x: Double, y: Double, w: Double, h: Double, angle: Double, px: Double, py: Double, tol: Double, solid: Boolean): Boolean {
        val (lx, ly) = local(px, py, x + w / 2.0, y + h / 2.0, angle)
        val dx = max(max(x - lx, lx - (x + w)), 0.0)
        val dy = max(max(y - ly, ly - (y + h)), 0.0)
        val outside = hypot(dx, dy)
        if (outside > 0.0) return outside <= tol
        if (solid) return true
        return min(min(lx - x, x + w - lx), min(ly - y, y + h - ly)) <= tol
    }

    private fun diamondHit(e: DiamondElement, px: Double, py: Double, tol: Double): Boolean {
        val x = e.x.value
        val y = e.y.value
        val w = e.width.value
        val h = e.height.value
        val cx = x + w / 2.0
        val cy = y + h / 2.0
        val (lx, ly) = local(px, py, cx, cy, e.angle)
        val ring = listOf(cx to y, x + w to cy, cx to y + h, x to cy)
        val nearEdge = ring.indices.any { i -> segmentDistance(lx, ly, ring[i].first, ring[i].second, ring[(i + 1) % ring.size].first, ring[(i + 1) % ring.size].second) <= tol }
        if (nearEdge) return true
        if (!filled(e) || w == 0.0 || h == 0.0) return false
        return abs(lx - cx) / (w / 2.0) + abs(ly - cy) / (h / 2.0) <= 1.0
    }

    private fun ellipseHit(e: EllipseElement, px: Double, py: Double, tol: Double): Boolean {
        val cx = e.cx.value
        val cy = e.cy.value
        val (lx, ly) = local(px, py, cx, cy, e.angle)
        val nx = lx - cx
        val ny = ly - cy
        val rx = e.rx.value
        val ry = e.ry.value
        val f = (nx / rx) * (nx / rx) + (ny / ry) * (ny / ry) - 1.0
        if (f <= 0.0 && filled(e)) return true
        return ringDistance(nx, ny, rx, ry, f) <= tol
    }

    /** Distance to an ellipse outline: exact for a circle, the first-order estimate |f| / |grad f| otherwise. */
    private fun ringDistance(nx: Double, ny: Double, rx: Double, ry: Double, f: Double): Double {
        if (rx == ry) return abs(hypot(nx, ny) - rx)
        val gx = 2.0 * nx / (rx * rx)
        val gy = 2.0 * ny / (ry * ry)
        val g = hypot(gx, gy)
        return if (g == 0.0) min(rx, ry) else abs(f) / g
    }

    private fun polylineHit(points: List<ScenePoint>, closed: Boolean, solid: Boolean, px: Double, py: Double, tol: Double): Boolean {
        if (points.isEmpty()) return false
        if (points.size == 1) return hypot(px - points[0].x.value, py - points[0].y.value) <= tol
        val last = if (closed) points.size else points.size - 1
        for (i in 0 until last) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            if (segmentDistance(px, py, a.x.value, a.y.value, b.x.value, b.y.value) <= tol) return true
        }
        return solid && insidePolygon(points, px, py)
    }

    private fun insidePolygon(points: List<ScenePoint>, px: Double, py: Double): Boolean {
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val yi = points[i].y.value
            val yj = points[j].y.value
            val xi = points[i].x.value
            val xj = points[j].x.value
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    companion object {
        internal fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared == 0.0) return hypot(px - ax, py - ay)
            val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
            return hypot(px - (ax + t * dx), py - (ay + t * dy))
        }

        /** Distance to the arc itself: the radial gap inside its sweep, the nearer end point outside it. */
        internal fun arcDistance(e: ArcElement, px: Double, py: Double): Double {
            val cx = e.cx.value
            val cy = e.cy.value
            val r = e.radius.value
            val sweep = positiveTurn(e.endAngle - e.startAngle)
            val along = positiveTurn(atan2(py - cy, px - cx) - e.startAngle)
            if (along <= sweep) return abs(hypot(px - cx, py - cy) - r)
            val start = hypot(px - (cx + r * cos(e.startAngle)), py - (cy + r * sin(e.startAngle)))
            val end = hypot(px - (cx + r * cos(e.endAngle)), py - (cy + r * sin(e.endAngle)))
            return min(start, end)
        }

        private fun positiveTurn(angle: Double): Double {
            val turn = angle % FULL_TURN
            return if (turn < 0.0) turn + FULL_TURN else turn
        }

        /** Screen pixels around a pointer that count as touching (UX: 6 px). */
        const val HIT_TOLERANCE_PX = 6.0

        private const val TRANSPARENT = "transparent"
        private const val FULL_TURN = 2.0 * PI
    }
}
