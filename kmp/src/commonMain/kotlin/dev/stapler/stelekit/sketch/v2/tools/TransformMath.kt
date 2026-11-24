// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** An unrotated box in scene units; rotation is carried beside it. */
data class Box(val x: Double, val y: Double, val width: Double, val height: Double) {
    val cx: Double get() = x + width / 2.0
    val cy: Double get() = y + height / 2.0
}

/**
 * Pure transform math for the Select tool (Task 3.2.2b): resizing one rotated element by a handle, scaling a group,
 * rotating about a centre with 15 degree snapping. Nothing here touches a scene.
 */
object TransformMath {
    /** An element cannot be resized below this many scene units on a side; dragging past the opposite edge stops here. */
    const val MIN_SIDE = 1.0

    /** Shift while rotating snaps to this many radians (15 degrees). */
    const val ROTATE_SNAP = PI / 12.0

    private const val HALF = 2.0
    private const val FULL_TURN = 2.0 * PI

    /**
     * The box after dragging [handle] of the box [box] (rotated by [angle]) by the world-space delta ([dx], [dy]).
     * The opposite handle stays put unless [fromCentre]. [keepAspect] applies to corner handles. The rotation is
     * unchanged, so a rotated element resizes along its own axes ("Resize keeps rotation").
     */
    fun resize(box: Box, angle: Double, handle: Handle, dx: Double, dy: Double, keepAspect: Boolean, fromCentre: Boolean): Box {
        val c = cos(angle)
        val s = sin(angle)
        val lx = dx * c + dy * s
        val ly = -dx * s + dy * c
        val grow = if (fromCentre) HALF else 1.0
        var w = if (handle.sx == 0) box.width else max(MIN_SIDE, box.width + handle.sx * lx * grow)
        var h = if (handle.sy == 0) box.height else max(MIN_SIDE, box.height + handle.sy * ly * grow)
        val hasArea = box.width > 0.0 && box.height > 0.0
        if (keepAspect && handle.isCorner && hasArea) {
            val k = max(w / box.width, h / box.height)
            w = max(MIN_SIDE, box.width * k)
            h = max(MIN_SIDE, box.height * k)
        }
        return place(box, angle, handle, w, h, fromCentre)
    }

    /**
     * [box] scaled by [k] about the handle opposite [handle] (about the centre when [fromCentre]), rotation unchanged: the
     * box a text element takes when its font is scaled by [k]. [k] must be positive.
     */
    fun scaleFromHandle(box: Box, angle: Double, handle: Handle, k: Double, fromCentre: Boolean): Box =
        place(box, angle, handle, box.width * k, box.height * k, fromCentre)

    private fun place(box: Box, angle: Double, handle: Handle, w: Double, h: Double, fromCentre: Boolean): Box {
        val c = cos(angle)
        val s = sin(angle)
        if (fromCentre) return Box(box.cx - w / HALF, box.cy - h / HALF, w, h)
        // The opposite point (the anchor) in world space stays fixed; the new centre is one half-box from it.
        val ax = -handle.sx * box.width / HALF
        val ay = -handle.sy * box.height / HALF
        val anchorX = box.cx + ax * c - ay * s
        val anchorY = box.cy + ax * s + ay * c
        val ox = handle.sx * w / HALF
        val oy = handle.sy * h / HALF
        val ncx = anchorX + ox * c - oy * s
        val ncy = anchorY + ox * s + oy * c
        return Box(ncx - w / HALF, ncy - h / HALF, w, h)
    }

    /** The absolute angle after dragging the rotation handle from [start] to [current] about ([cx], [cy]). */
    fun rotate(cx: Double, cy: Double, startAngle: Double, startX: Double, startY: Double, curX: Double, curY: Double, snap: Boolean): Double {
        val delta = atan2(curY - cy, curX - cx) - atan2(startY - cy, startX - cx)
        val raw = startAngle + delta
        return normalise(if (snap) (raw / ROTATE_SNAP).roundToInt() * ROTATE_SNAP else raw)
    }

    /** Folds [angle] into (-PI, PI]. */
    fun normalise(angle: Double): Double {
        var a = angle % FULL_TURN
        if (a > PI) a -= FULL_TURN
        if (a <= -PI) a += FULL_TURN
        return a + 0.0
    }

    /** [point] rotated by [delta] about ([cx], [cy]). */
    fun rotatePoint(cx: Double, cy: Double, px: Double, py: Double, delta: Double): Pair<Double, Double> {
        val c = cos(delta)
        val s = sin(delta)
        return (cx + (px - cx) * c - (py - cy) * s) to (cy + (px - cx) * s + (py - cy) * c)
    }

    /** Maps [element] of the group box [from] to where it lands when the group box becomes [to]. */
    fun mapBox(element: Box, from: Box, to: Box): Box {
        val kx = if (from.width == 0.0) 1.0 else to.width / from.width
        val ky = if (from.height == 0.0) 1.0 else to.height / from.height
        return Box(
            to.x + (element.x - from.x) * kx,
            to.y + (element.y - from.y) * ky,
            element.width * kx,
            element.height * ky,
        )
    }
}
