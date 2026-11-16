// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.binding

import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.BindingMode
import dev.stapler.stelekit.sketch.v2.scene.BoxElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The unrotated box of a bindable shape and its rotation about the centre (an ellipse is its bounding box). */
internal data class BindFrame(val x: Double, val y: Double, val w: Double, val h: Double, val angle: Double) {
    val cx: Double get() = x + w / HALF
    val cy: Double get() = y + h / HALF

    private companion object {
        const val HALF = 2.0
    }
}

/** A position in scene units. */
typealias XY = Pair<Double, Double>

/**
 * Where an arrow end bound to a shape sits (Story 3.3.1): `Binding.fixedX/fixedY` are fractions of the target's own box,
 * so the end is `box origin + fraction * size` rotated about the centre with the target. Moving, resizing or rotating the
 * target therefore moves the end with it; the arrow tool picks the fraction by projecting the drop point onto the outline.
 * Pure geometry over `scene` types: no scene lookups, no limits.
 */
object BindingGeometry {
    private const val HALF = 2.0
    private const val CENTRE_FRACTION = 0.5

    /** The kinds an arrow end may bind to (Story 3.3.1 triage): rectangle, diamond, ellipse, text, image and frame. */
    fun isBindable(e: SceneElement): Boolean = frameOf(e) != null

    internal fun frameOf(e: SceneElement): BindFrame? = when (e) {
        is EllipseElement -> BindFrame(e.cx.value - e.rx.value, e.cy.value - e.ry.value, e.rx.value * HALF, e.ry.value * HALF, e.angle)
        is BoxElement -> BindFrame(e.x.value, e.y.value, e.width.value, e.height.value, e.angle)
        else -> null
    }

    /** The scene position of [binding] on [target]; null when [target] cannot be bound to. */
    fun pointOf(target: SceneElement, binding: Binding): XY? {
        val f = frameOf(target) ?: return null
        return rotate(f.x + binding.fixedX * f.w, f.y + binding.fixedY * f.h, f.cx, f.cy, f.angle)
    }

    /**
     * The Orbit binding for a drop at ([px], [py]): the nearest point of the target's outline, as box fractions.
     * A rectangle-like target clamps to its box and pushes an inside point to the nearest edge; a diamond projects onto its
     * nearest edge; an ellipse projects along the ray from its centre.
     */
    fun bindingAt(target: SceneElement, px: Double, py: Double): Binding? {
        val f = frameOf(target) ?: return null
        val (lx, ly) = rotate(px, py, f.cx, f.cy, -f.angle)
        val (ox, oy) = outline(target, f, lx, ly)
        return Binding(target.id, fraction(ox - f.x, f.w), fraction(oy - f.y, f.h), BindingMode.Orbit)
    }

    /**
     * How far ([px], [py]) is from where a drop would bind to [target]: zero inside a filled shape, the gap to the outline
     * outside it. A frame binds only near its border so the elements inside it stay reachable.
     */
    fun reach(target: SceneElement, px: Double, py: Double): Double {
        val f = frameOf(target) ?: return Double.MAX_VALUE
        val (lx, ly) = rotate(px, py, f.cx, f.cy, -f.angle)
        val inside = when (target) {
            is EllipseElement -> insideEllipse(f, lx, ly)
            is DiamondElement -> insideDiamond(f, lx, ly)
            else -> lx in f.x..(f.x + f.w) && ly in f.y..(f.y + f.h)
        }
        if (inside && target !is FrameElement) return 0.0
        val (ox, oy) = outline(target, f, lx, ly)
        return hypot(lx - ox, ly - oy)
    }

    private fun outline(target: SceneElement, f: BindFrame, lx: Double, ly: Double): XY = when (target) {
        is EllipseElement -> ellipseOutline(f, lx, ly)
        is DiamondElement -> diamondOutline(f, lx, ly)
        else -> boxOutline(f, lx, ly)
    }

    private fun fraction(offset: Double, size: Double): Double = if (size == 0.0) CENTRE_FRACTION else offset / size

    private fun rotate(x: Double, y: Double, cx: Double, cy: Double, angle: Double): XY {
        if (angle == 0.0) return x to y
        val c = cos(angle)
        val s = sin(angle)
        return (cx + (x - cx) * c - (y - cy) * s) to (cy + (x - cx) * s + (y - cy) * c)
    }

    private fun boxOutline(f: BindFrame, lx: Double, ly: Double): XY {
        val cx = lx.coerceIn(f.x, f.x + f.w)
        val cy = ly.coerceIn(f.y, f.y + f.h)
        if (cx != lx || cy != ly) return cx to cy
        val left = cx - f.x
        val right = f.x + f.w - cx
        val top = cy - f.y
        val bottom = f.y + f.h - cy
        return when (min(min(left, right), min(top, bottom))) {
            left -> f.x to cy
            right -> (f.x + f.w) to cy
            top -> cx to f.y
            else -> cx to (f.y + f.h)
        }
    }

    private fun ellipseOutline(f: BindFrame, lx: Double, ly: Double): XY {
        val rx = f.w / HALF
        val ry = f.h / HALF
        val dx = lx - f.cx
        val dy = ly - f.cy
        val norm = sqrt((dx / rx) * (dx / rx) + (dy / ry) * (dy / ry))
        return if (norm == 0.0 || !norm.isFinite()) (f.cx + rx) to f.cy else (f.cx + dx / norm) to (f.cy + dy / norm)
    }

    private fun insideEllipse(f: BindFrame, lx: Double, ly: Double): Boolean {
        val dx = (lx - f.cx) / (f.w / HALF)
        val dy = (ly - f.cy) / (f.h / HALF)
        return dx * dx + dy * dy <= 1.0
    }

    private fun insideDiamond(f: BindFrame, lx: Double, ly: Double): Boolean =
        abs(lx - f.cx) / (f.w / HALF) + abs(ly - f.cy) / (f.h / HALF) <= 1.0

    private fun diamondOutline(f: BindFrame, lx: Double, ly: Double): XY {
        val top = f.cx to f.y
        val right = (f.x + f.w) to f.cy
        val bottom = f.cx to (f.y + f.h)
        val left = f.x to f.cy
        val edges = listOf(top to right, right to bottom, bottom to left, left to top)
        return edges.map { (a, b) -> nearestOnSegment(lx, ly, a, b) }.minBy { hypot(lx - it.first, ly - it.second) }
    }

    private fun nearestOnSegment(px: Double, py: Double, a: XY, b: XY): XY {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else max(0.0, min(1.0, ((px - a.first) * dx + (py - a.second) * dy) / len2))
        return (a.first + t * dx) to (a.second + t * dy)
    }
}
