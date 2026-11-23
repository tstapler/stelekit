// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.BoxElement
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A rectangle in canvas dp. */
data class ShapeRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val centreX: Double get() = (left + right) / 2
    val centreY: Double get() = (top + bottom) / 2

    fun shiftedY(dy: Double) = ShapeRect(left, top + dy, right, bottom + dy)

    fun intersects(o: ShapeRect): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/** Where the size field goes and how far the view must pan (dp, negative is up) so shape and field are both visible. */
data class EditorPlan(val left: Double, val top: Double, val panDy: Double)

/**
 * Places the inline size field. It never covers the shape being sized: it goes on the side of the shape the tapped pill is on,
 * then the other sides, all inside the part of the canvas the soft keyboard leaves visible. A shape that sits under the
 * keyboard is panned up first. Pure, so the keyboard case is tested without a keyboard.
 */
object PillEditorPlacement {
    private const val MIN_SHAPE_VISIBLE_DP = 56.0

    /** The on-screen bounds of [element] in dp (a rotated box by its rotated corners). */
    fun shapeBounds(element: SceneElement, canvas: PillCanvas): ShapeRect? {
        if (element is LineElement) {
            val pts = element.points.map { canvas.toScreenDp(it.x.value, it.y.value) }
            if (pts.isEmpty()) return null
            return ShapeRect(pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first }, pts.maxOf { it.second })
        }
        val s = ElementStats.of(element) ?: return null
        val (cx, cy) = canvas.toScreenDp(s.x + s.width / 2, s.y + s.height / 2)
        val angle = (element as? BoxElement)?.angle ?: (element as? EllipseElement)?.angle ?: 0.0
        val hw = s.width / 2 * canvas.dpPerUnit
        val hh = s.height / 2 * canvas.dpPerUnit
        val ex = abs(cos(angle)) * hw + abs(sin(angle)) * hh
        val ey = abs(sin(angle)) * hw + abs(cos(angle)) * hh
        return ShapeRect(cx - ex, cy - ey, cx + ex, cy + ey)
    }

    fun plan(shape: ShapeRect, anchor: PlacedPill?, canvasW: Double, visibleH: Double, editorW: Double, editorH: Double): EditorPlan {
        val edge = DimensionPillModel.EDGE_DP
        val gap = DimensionPillModel.GAP_DP
        val pan = if (shape.top > visibleH - MIN_SHAPE_VISIBLE_DP) editorH + 2 * edge + gap - shape.top else 0.0
        val s = shape.shiftedY(pan)
        val anchorX = anchor?.centreX ?: s.centreX
        val minLeft = edge
        val maxLeft = max(edge, canvasW - editorW - edge)
        val centredLeft = (anchorX - editorW / 2).coerceIn(minLeft, maxLeft)
        val sideTop = (s.centreY - editorH / 2).coerceIn(edge, max(edge, visibleH - editorH - edge))
        val candidates = sidesFor(anchor, s).map { side ->
            when (side) {
                Side.Above -> EditorPlan(centredLeft, s.top - gap - editorH, pan)
                Side.Below -> EditorPlan(centredLeft, s.bottom + gap, pan)
                Side.Right -> EditorPlan(s.right + gap, sideTop, pan)
                Side.Left -> EditorPlan(s.left - gap - editorW, sideTop, pan)
            }
        }
        val fits = candidates.firstOrNull { p ->
            val box = ShapeRect(p.left, p.top, p.left + editorW, p.top + editorH)
            box.left >= edge && box.top >= edge && box.right <= canvasW - edge && box.bottom <= visibleH - edge && !box.intersects(s)
        }
        // No free side (a shape that fills the view): the field takes the bottom of what the keyboard leaves, over the shape.
        return fits ?: EditorPlan(centredLeft, max(edge, visibleH - editorH - edge), pan)
    }

    private enum class Side { Above, Below, Right, Left }

    private fun sidesFor(anchor: PlacedPill?, s: ShapeRect): List<Side> = when {
        anchor == null -> listOf(Side.Above, Side.Below, Side.Right, Side.Left)
        anchor.centreY >= s.bottom -> listOf(Side.Below, Side.Above, Side.Right, Side.Left)
        anchor.centreY <= s.top -> listOf(Side.Above, Side.Below, Side.Right, Side.Left)
        anchor.centreX >= s.centreX -> listOf(Side.Right, Side.Below, Side.Above, Side.Left)
        else -> listOf(Side.Left, Side.Below, Side.Above, Side.Right)
    }

    /** The visible height of a canvas of [canvasH] dp whose bottom is covered by the keyboard for [overlapDp]. */
    fun visibleHeight(canvasH: Double, overlapDp: Double): Double = min(canvasH, max(canvasH - overlapDp, 0.0))
}
