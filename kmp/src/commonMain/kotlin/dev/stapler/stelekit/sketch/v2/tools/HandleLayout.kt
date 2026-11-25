// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The nine on-canvas selection handles: eight resize handles by compass point and the rotation handle. */
enum class Handle(val sx: Int, val sy: Int) {
    NW(-1, -1), N(0, -1), NE(1, -1), E(1, 0), SE(1, 1), S(0, 1), SW(-1, 1), W(-1, 0), Rotate(0, 0);

    val isCorner: Boolean get() = sx != 0 && sy != 0
}

/** How many handles a selection of a given on-screen size can show without their hit areas overlapping (UX-127). */
enum class HandleTier {
    Full, Corners,

    /** A selection too small for the floor-sized corners: the four corners with a reach clamped to a fraction of the shape. */
    Compact,
    None,
}

/**
 * The touch-target floor in density-independent pixels (UX-46, UX-92, D-17): 44 whenever a touch or pen pointer is
 * present, 24 on a mouse-only device. [pxPerDp] converts to screen pixels.
 */
object TouchFloor {
    const val TOUCH_DP = 44.0
    const val MOUSE_DP = 24.0

    fun dp(touchPresent: Boolean): Double = if (touchPresent) TOUCH_DP else MOUSE_DP
}

/** One shown handle: where it is (scene units, rotation applied) and the half side of its square hit area (scene units). */
data class PlacedHandle(val handle: Handle, val centre: ScenePoint, val halfHit: Double, val hitCentre: ScenePoint = centre)

/** The handles to draw and hit-test for one selection (Task 3.2.2d, the computed tiers of ux.md section 1). */
data class HandleLayout(val tier: HandleTier, val handles: List<PlacedHandle>) {
    /** True when no handles fit: the host says "Zoom in to use handles" and offers the Transform menu (UX-127). */
    val needsTransformMenu: Boolean get() = tier == HandleTier.None

    /** The handle whose hit area contains ([x], [y]); the rotation handle and corners win over edges when areas touch. */
    fun hit(x: Double, y: Double): Handle? = handles
        .filter { abs(x - it.hitCentre.x.value) <= it.halfHit && abs(y - it.hitCentre.y.value) <= it.halfHit }
        .minByOrNull { if (it.handle == Handle.Rotate) 0 else if (it.handle.isCorner) 1 else 2 }
        ?.handle

    companion object {
        /** `handleFull` of the chrome table: this many floors along the shorter side show all eight resize handles. */
        const val FULL_FLOORS = 3.0

        /** `handleCorners` of the chrome table: this many floors show the four corners. */
        const val CORNER_FLOORS = 2.0

        /** The rotation handle sits this many floors above the top edge, so its hit area clears the top-middle handle. */
        const val ROTATE_OFFSET_FLOORS = 1.25

        private const val HALF = 2.0

        /**
         * Layout for a (possibly rotated) box of [width] x [height] scene units about ([cx], [cy]) at [zoom] screen
         * pixels per unit with a [floorPx] hit area. The tier follows the shorter side on screen; [rotatable] false drops
         * the rotation handle (a selection holding a line cannot rotate).
         */
        fun compute(cx: Double, cy: Double, width: Double, height: Double, angle: Double, zoom: Double, floorPx: Double, rotatable: Boolean = true): HandleLayout {
            val shorterPx = minOf(width, height) * zoom
            val tier = when {
                shorterPx >= FULL_FLOORS * floorPx -> HandleTier.Full
                shorterPx >= CORNER_FLOORS * floorPx -> HandleTier.Corners
                else -> HandleTier.None
            }
            if (tier == HandleTier.None) return HandleLayout(tier, emptyList())
            val half = floorPx / zoom / HALF
            val c = cos(angle)
            val s = sin(angle)
            fun place(h: Handle, lx: Double, ly: Double) = PlacedHandle(
                h, ScenePoint(unit(cx + lx * c - ly * s), unit(cy + lx * s + ly * c)), half,
            )
            val shown = Handle.entries.filter { it != Handle.Rotate && (tier == HandleTier.Full || it.isCorner) }
            val placed = shown.map { place(it, it.sx * width / HALF, it.sy * height / HALF) }.toMutableList()
            if (rotatable) placed += place(Handle.Rotate, 0.0, -height / HALF - ROTATE_OFFSET_FLOORS * floorPx / zoom)
            return HandleLayout(tier, placed)
        }

        /** How far in from a side a corner's hit area may reach, as a share of the shorter side: the middle of a small shape stays its body. */
        const val COMPACT_REACH = 0.3

        /** How much of the inward reach a compact corner keeps outward: half, so a neighbouring shape beside a small one stays selectable. */
        const val COMPACT_OUTWARD_SHARE = 0.5

        /**
         * The four corner handles of a box too small for [compute]'s tiers (the user must still be able to resize it without
         * moving it). Each hit area is the floor, clamped to [COMPACT_REACH] of the shorter side, so the corners never swallow the
         * whole shape; the press nearest a corner wins over the body because the Select tool tries handles first. A box with no
         * area (a straight line) has no corners and gets none.
         */
        fun compact(cx: Double, cy: Double, width: Double, height: Double, angle: Double, zoom: Double, floorPx: Double, rotatable: Boolean = false): HandleLayout {
            val shorter = minOf(width, height)
            if (shorter <= 0.0) return degenerate(cx, cy, height, angle, zoom, floorPx, rotatable)
            // Reach inward as far as the floor allows (clamped to a share of the shape), but only part of that outward, so a press
            // just outside a small selected shape still selects the neighbour there instead of resizing this one.
            val inward = minOf(floorPx / zoom / HALF, COMPACT_REACH * shorter)
            val outward = inward * COMPACT_OUTWARD_SHARE
            val half = (inward + outward) / HALF
            val shift = (inward - outward) / HALF
            val c = cos(angle)
            val s = sin(angle)
            val placed = Handle.entries.filter { it.isCorner }.map { h ->
                val lx = h.sx * width / HALF
                val ly = h.sy * height / HALF
                val hx = lx - h.sx * shift
                val hy = ly - h.sy * shift
                PlacedHandle(
                    h,
                    ScenePoint(unit(cx + lx * c - ly * s), unit(cy + lx * s + ly * c)),
                    half,
                    ScenePoint(unit(cx + hx * c - hy * s), unit(cy + hx * s + hy * c)),
                )
            }
            val corners = placed.toMutableList()
            if (rotatable) {
                val ly = -height / HALF - ROTATE_OFFSET_FLOORS * floorPx / zoom
                corners += PlacedHandle(Handle.Rotate, ScenePoint(unit(cx - ly * s), unit(cy + ly * c)), floorPx / zoom / HALF * COMPACT_OUTWARD_SHARE)
            }
            return HandleLayout(HandleTier.Compact, corners)
        }

        /** A straight horizontal or vertical run has no corners, but it can still be turned: the rotate handle alone, above its middle. */
        private fun degenerate(cx: Double, cy: Double, height: Double, angle: Double, zoom: Double, floorPx: Double, rotatable: Boolean): HandleLayout {
            if (!rotatable) return HandleLayout(HandleTier.None, emptyList())
            val ly = -height / HALF - ROTATE_OFFSET_FLOORS * floorPx / zoom
            val at = ScenePoint(unit(cx - ly * sin(angle)), unit(cy + ly * cos(angle)))
            return HandleLayout(HandleTier.Compact, listOf(PlacedHandle(Handle.Rotate, at, floorPx / zoom / HALF)))
        }

        private fun unit(v: Double) = dev.stapler.stelekit.sketch.v2.scene.SceneUnit.of(v).getOrNull() ?: dev.stapler.stelekit.sketch.v2.scene.SceneUnit.ZERO
    }
}
