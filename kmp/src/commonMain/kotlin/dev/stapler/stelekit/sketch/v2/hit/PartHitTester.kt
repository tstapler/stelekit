// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.hit

import dev.stapler.stelekit.sketch.v2.constraints.ConstraintParts
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One constraint target found under a pointer: the element, the part of it, and how far the part is in scene units. */
data class PartHit(val elementId: ElementId, val part: TargetPart, val distance: Double)

/**
 * Finds which part of an element a pointer is over (Task 4.2.6a). Priority is point-like parts (a point element, an
 * endpoint, a vertex, a centre, an arc end) over an edge over a whole curve, then the nearest part, then the topmost
 * element. The tolerance is [PartHitTester.MOUSE_TOLERANCE_PX] for a mouse or stylus and the touch half-floor for touch,
 * both divided by the zoom. Elements on a hidden layer are never targets; locked ones are, because a constraint moves
 * nothing it does not have to.
 */
class PartHitTester(private val scene: SketchSceneV2, private val index: SpatialIndex) {
    private val arrayIndex: Map<ElementId, Int> by lazy { scene.order.withIndex().associate { it.value to it.index } }

    /** The best part at [point], or null when none is within tolerance. */
    fun hitPart(point: ScenePoint, zoom: Double, toleranceScreen: Double = MOUSE_TOLERANCE_PX): PartHit? =
        hitParts(point, zoom, toleranceScreen).firstOrNull()

    /** Every part within tolerance, best first; the picker lists the runners-up ("also: Line 4 start"). */
    fun hitParts(point: ScenePoint, zoom: Double, toleranceScreen: Double = MOUSE_TOLERANCE_PX): List<PartHit> {
        if (!isUsable(zoom) || !isUsable(toleranceScreen)) return emptyList()
        val tolerance = toleranceScreen / zoom
        val px = point.x.value
        val py = point.y.value
        val layers = scene.layers.associateBy { it.id }
        val ranked = ArrayList<Ranked>()
        for (id in index.query(Bounds.around(px, py, tolerance))) {
            val element = scene[id] ?: continue
            val layer = layers[element.base.layerId] ?: Layer.DefaultLayer
            if (!layer.visible) continue
            for (part in ConstraintParts.partsOf(element)) {
                val distance = distanceTo(element, part, px, py)
                if (distance <= tolerance) ranked += Ranked(PartHit(id, part, distance), tierOf(element, part), layer.order, element.base.zKey, arrayIndex.getValue(id))
            }
        }
        return ranked.sortedWith(
            compareBy<Ranked> { it.tier }
                .thenBy { it.hit.distance }
                .thenByDescending { it.layerOrder }
                .thenByDescending { it.zKey }
                .thenByDescending { it.arrayIndex },
        ).map { it.hit }
    }

    private fun isUsable(value: Double): Boolean = value.isFinite() && value > 0.0

    private class Ranked(val hit: PartHit, val tier: Int, val layerOrder: Int, val zKey: ZKey, val arrayIndex: Int)

    private fun tierOf(element: SceneElement, part: TargetPart): Int = when {
        part.isPoint || element is PointElement -> TIER_POINT
        part is TargetPart.Edge -> TIER_EDGE
        else -> TIER_WHOLE
    }

    private fun distanceTo(element: SceneElement, part: TargetPart, px: Double, py: Double): Double =
        when (element) {
            is PointElement -> hypot(px - element.x.value, py - element.y.value)
            is LineElement -> linear(element.points.map { it.x.value to it.y.value }, if (element.polygon) element.points.size else element.points.size - 1, part, px, py)
            is RectElement -> linear(cornersOf(element), RECT_CORNERS, part, px, py)
            is EllipseElement -> circleDistance(element, part, px, py)
            is ArcElement -> arcPartDistance(element, part, px, py)
            else -> Double.POSITIVE_INFINITY
        }

    private fun linear(points: List<Pair<Double, Double>>, edges: Int, part: TargetPart, px: Double, py: Double): Double {
        fun at(i: Int) = points.getOrNull(i)
        return when (part) {
            TargetPart.Start -> pointDistance(at(0), px, py)
            TargetPart.End -> pointDistance(at(points.size - 1), px, py)
            is TargetPart.Vertex -> pointDistance(at(part.index), px, py)
            is TargetPart.Edge -> {
                val a = at(part.index)
                val b = at((part.index + 1) % points.size)
                if (part.index >= edges || a == null || b == null) Double.POSITIVE_INFINITY
                else HitTester.segmentDistance(px, py, a.first, a.second, b.first, b.second)
            }
            else -> Double.POSITIVE_INFINITY
        }
    }

    private fun pointDistance(p: Pair<Double, Double>?, px: Double, py: Double): Double =
        if (p == null) Double.POSITIVE_INFINITY else hypot(px - p.first, py - p.second)

    private fun circleDistance(e: EllipseElement, part: TargetPart, px: Double, py: Double): Double = when (part) {
        TargetPart.Centre -> hypot(px - e.cx.value, py - e.cy.value)
        TargetPart.Whole -> abs(hypot(px - e.cx.value, py - e.cy.value) - e.rx.value)
        else -> Double.POSITIVE_INFINITY
    }

    private fun arcPartDistance(e: ArcElement, part: TargetPart, px: Double, py: Double): Double {
        val cx = e.cx.value
        val cy = e.cy.value
        val r = e.radius.value
        return when (part) {
            TargetPart.Centre -> hypot(px - cx, py - cy)
            TargetPart.ArcStart -> hypot(px - (cx + r * cos(e.startAngle)), py - (cy + r * sin(e.startAngle)))
            TargetPart.ArcEnd -> hypot(px - (cx + r * cos(e.endAngle)), py - (cy + r * sin(e.endAngle)))
            TargetPart.Whole -> HitTester.arcDistance(e, px, py)
            else -> Double.POSITIVE_INFINITY
        }
    }

    companion object {
        /** Screen pixels around a mouse or stylus pointer that reach a part. */
        const val MOUSE_TOLERANCE_PX = HitTester.HIT_TOLERANCE_PX

        private const val TIER_POINT = 0
        private const val TIER_EDGE = 1
        private const val TIER_WHOLE = 2
        private const val RECT_CORNERS = 4

        /** The corners of [r] in the order the solver uses (top-left, top-right, bottom-right, bottom-left), rotated about its centre. */
        fun cornersOf(r: RectElement): List<Pair<Double, Double>> {
            val x0 = r.x.value
            val y0 = r.y.value
            val x1 = x0 + r.width.value
            val y1 = y0 + r.height.value
            val raw = listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)
            if (r.angle == 0.0) return raw
            val cx = (x0 + x1) / 2.0
            val cy = (y0 + y1) / 2.0
            val c = cos(r.angle)
            val s = sin(r.angle)
            return raw.map { (x, y) -> (cx + (x - cx) * c - (y - cy) * s) to (cy + (x - cx) * s + (y - cy) * c) }
        }
    }
}
