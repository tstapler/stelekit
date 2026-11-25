// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.binding

import dev.stapler.stelekit.sketch.v2.codec.CanonicalJson
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.routing.ElbowRouter
import dev.stapler.stelekit.sketch.v2.routing.RouteEnd
import dev.stapler.stelekit.sketch.v2.routing.RoutePoint
import dev.stapler.stelekit.sketch.v2.routing.RouteRect
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The coordinate the user dragged an elbow arrow's middle segment to, kept in the arrow's `extras` (key `elbowMid`) so no schema field changes. */
object ElbowMid {
    const val KEY = "elbowMid"

    fun read(extras: JsonObject): Double? = (extras[KEY] as? JsonPrimitive)?.let(CanonicalJson::doubleOf)?.takeIf { it.isFinite() }

    /** [extras] with the coordinate stored; null removes it. */
    fun write(extras: JsonObject, value: Double?): JsonObject {
        if (value == null) return JsonObject(extras - KEY)
        val stored = CanonicalJson.number(value).getOrNull() ?: return extras
        return JsonObject(extras + (KEY to stored))
    }
}

/** The grab point of an elbow arrow's middle segment: dragging it along the other axis moves that segment. */
data class MidHandle(val x: Double, val y: Double, val vertical: Boolean)

/**
 * Routes the elbow arrows of a scene (epic E11): an arrow with the elbow flag and at least one bound end is drawn as the orthogonal
 * route of [ElbowRouter] between its ends, leaving each bound shape along the side the binding sits on. An elbow arrow with no bound
 * end keeps the points it was drawn with.
 */
object ElbowRouting {
    private const val HALF = 2.0
    private const val CENTRE = 0.5
    private const val Z_POINTS = 4
    private const val MIN_POINTS = 2
    private const val EPS = 1e-9

    /** Where the draggable middle segment of [arrow]'s route sits: its midpoint, and whether it runs vertically; null unless the route has exactly two bends. */
    fun midHandle(arrow: ArrowElement): MidHandle? {
        if (!arrow.elbowed || arrow.points.size != Z_POINTS) return null
        if (arrow.startBinding == null && arrow.endBinding == null) return null
        val (b, c) = arrow.points[1] to arrow.points[2]
        val vertical = abs(b.x.value - c.x.value) <= EPS
        val horizontal = abs(b.y.value - c.y.value) <= EPS
        if (!vertical && !horizontal) return null
        return MidHandle((b.x.value + c.x.value) / HALF, (b.y.value + c.y.value) / HALF, vertical)
    }

    /** The routed points of [arrow], or null when it is not an elbow arrow, has no bound end, or its binding cannot be resolved. */
    fun routeOf(scene: SketchSceneV2, arrow: ArrowElement): List<RoutePoint>? {
        if (!arrow.elbowed || arrow.points.size < MIN_POINTS) return null
        if (arrow.startBinding == null && arrow.endBinding == null) return null
        val first = arrow.points.first()
        val last = arrow.points.last()
        val start = endOf(scene, arrow.startBinding, first)
        val end = endOf(scene, arrow.endBinding, last)
        return ElbowRouter.route(start, end, ElbowMid.read(arrow.base.extras))
    }

    /** [routeOf] as scene points, or null (also null when a coordinate would leave the scene's bounds). */
    fun pointsOf(scene: SketchSceneV2, arrow: ArrowElement): List<ScenePoint>? {
        val route = routeOf(scene, arrow) ?: return null
        val out = route.map { p ->
            ScenePoint(SceneUnit.of(p.x, scene.limits).getOrNull() ?: return null, SceneUnit.of(p.y, scene.limits).getOrNull() ?: return null)
        }
        return out
    }

    private fun endOf(scene: SketchSceneV2, binding: Binding?, drawn: ScenePoint): RouteEnd {
        val target = binding?.let { scene[it.elementId] }
        val at = target?.let { t -> BindingGeometry.pointOf(t, binding) }
        if (target == null || binding == null || at == null) return RouteEnd(drawn.x.value, drawn.y.value)
        val (dx, dy) = sideOf(target, binding)
        val box = ElementBounds.geometry(target)
        val rect = RouteRect(box.minX, box.minY, box.maxX, box.maxY)
        // A turned shape's side midpoint lies inside its bounding box: the stub first runs out to the box, then the usual margin.
        val inside = when {
            dx > 0 -> rect.maxX - at.first
            dx < 0 -> at.first - rect.minX
            dy > 0 -> rect.maxY - at.second
            else -> at.second - rect.minY
        }.coerceAtLeast(0.0)
        return RouteEnd(at.first, at.second, dx, dy, rect, ElbowRouter.MARGIN + inside)
    }

    /** The axis direction (a unit step) the side of [target] that [binding] sits on faces, turned with the shape to the nearest axis. */
    internal fun sideOf(target: SceneElement, binding: Binding): Pair<Int, Int> {
        val f = BindingGeometry.frameOf(target) ?: return 1 to 0
        val lx = (binding.fixedX - CENTRE) * f.w
        val ly = (binding.fixedY - CENTRE) * f.h
        val nx = if (f.w == 0.0) 0.0 else abs(lx) / (f.w / HALF)
        val ny = if (f.h == 0.0) 0.0 else abs(ly) / (f.h / HALF)
        val (ux, uy) = if (nx >= ny) (if (lx >= 0.0) 1.0 else -1.0) to 0.0 else 0.0 to (if (ly >= 0.0) 1.0 else -1.0)
        val c = cos(f.angle)
        val s = sin(f.angle)
        val rx = ux * c - uy * s
        val ry = ux * s + uy * c
        return if (abs(rx) >= abs(ry)) (if (rx >= 0.0) 1 else -1) to 0 else 0 to (if (ry >= 0.0) 1 else -1)
    }

    /** The port (side midpoint) of [target] nearest [binding], as a binding; [binding] itself when it is not within [tolerance] of a fraction of a side. */
    fun snapToPort(binding: Binding, tolerance: Double): Binding {
        val candidates = listOf(CENTRE to 0.0, CENTRE to 1.0, 0.0 to CENTRE, 1.0 to CENTRE)
        val near = candidates.filter { (px, py) -> abs(binding.fixedX - px) <= tolerance && abs(binding.fixedY - py) <= tolerance }
        val best = near.minByOrNull { (px, py) -> abs(binding.fixedX - px) + abs(binding.fixedY - py) } ?: return binding
        return binding.copy(fixedX = best.first, fixedY = best.second)
    }
}
