// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/** The circular arc through a start, an end and a bulge point (Story 3.2.4a, OD-8). Pure. */
object ArcFromThreePoints {
    /** A cross product smaller than this fraction of the squared span counts as collinear. */
    private const val COLLINEAR_RELATIVE = 1e-9
    private const val TWO = 2.0
    private const val FULL_TURN = 2.0 * PI

    /**
     * The arc from [start] to [end] that passes through [bulge], counter-clockwise from the start angle to the end
     * angle, so `endAngle > startAngle` and the radius is positive. Null for collinear or coincident points and when
     * the radius would leave the coordinate range.
     */
    fun of(start: ScenePoint, end: ScenePoint, bulge: ScenePoint, base: ElementBase, limits: SceneLimits = SceneLimits.Default): ArcElement? {
        val ax = start.x.value
        val ay = start.y.value
        val bx = end.x.value
        val by = end.y.value
        val cx = bulge.x.value
        val cy = bulge.y.value
        val cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        val span = max(max(hypot(bx - ax, by - ay), hypot(cx - ax, cy - ay)), hypot(cx - bx, cy - by))
        if (span == 0.0 || abs(cross) <= COLLINEAR_RELATIVE * span * span) return null
        val d = TWO * cross
        val a2 = ax * ax + ay * ay
        val b2 = bx * bx + by * by
        val c2 = cx * cx + cy * cy
        // Circumcentre from the perpendicular bisectors.
        val ox = ((cy - ay) * (b2 - a2) - (by - ay) * (c2 - a2)) / d
        val oy = ((bx - ax) * (c2 - a2) - (cx - ax) * (b2 - a2)) / d
        val radius = hypot(ax - ox, ay - oy)
        val aStart = atan2(ay - oy, ax - ox)
        val aEnd = atan2(by - oy, bx - ox)
        val aBulge = atan2(cy - oy, cx - ox)
        val startToEnd = turn(aEnd - aStart)
        val startToBulge = turn(aBulge - aStart)
        val (from, sweep) = if (startToBulge < startToEnd) aStart to startToEnd else aEnd to turn(aStart - aEnd)
        return ArcElement.create(
            base,
            SceneUnit.of(ox, limits).getOrNull() ?: return null,
            SceneUnit.of(oy, limits).getOrNull() ?: return null,
            SceneUnit.of(radius, limits).getOrNull() ?: return null,
            from,
            from + sweep,
        ).getOrNull()
    }

    private fun turn(angle: Double): Double {
        val t = angle % FULL_TURN
        return if (t < 0.0) t + FULL_TURN else t
    }
}

/**
 * Arc tool (Story 3.2.4b, OD-8): three clicks place the start, the end and a point the arc bulges through; there is
 * no drag, so it works with the Crosshair and with a keyboard. Hovering after the second point previews the arc.
 * Collinear points commit nothing and raise [ToolNotice.NothingDrawn]; Esc abandons the placed points with no command.
 */
class ArcTool(private val placed: List<ScenePoint> = emptyList()) : Tool {
    override val id: ToolId = ToolId.Arc

    /** The points placed so far (0 to 2), for the keyboard flow's mid-draw check. */
    val placedPoints: List<ScenePoint> get() = placed

    override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = when (event.type) {
        InputType.Down -> ToolResult(next = Pressed(this, event.pointerId))
        InputType.Move -> if (placed.isEmpty()) ToolResult.Nothing else ToolResult(preview = previewWith(event.point, ctx))
        InputType.Escape, InputType.Cancel -> if (placed.isEmpty()) ToolResult.Nothing else ToolResult(preview = null, next = ArcTool())
        InputType.Up -> ToolResult.Nothing
    }

    /** What the next click would commit or show, for the status line and the hover preview. */
    fun hint(): String = when (placed.size) {
        0 -> "Click the arc's start point"
        1 -> "Click the arc's end point"
        else -> "Click a point the arc passes through"
    }

    private fun previewWith(cursor: ScenePoint, ctx: ToolContext): ToolPreview? {
        val base = ElementBase(PREVIEW_ID, style = ctx.style)
        return when (placed.size) {
            0 -> null
            1 -> ToolPreview(listOf(LineElement(base, listOf(placed[0], cursor))))
            else -> ToolPreview(listOf(ArcFromThreePoints.of(placed[0], placed[1], cursor, base) ?: LineElement(base, listOf(placed[0], placed[1]))))
        }
    }

    private fun place(at: ScenePoint, ctx: ToolContext): ToolResult {
        val points = placed + at
        if (points.size < ARC_POINTS) {
            val marker = ToolPreview(listOf(PointElement(ElementBase(PREVIEW_ID, style = ctx.style), at.x, at.y)))
            return ToolResult(preview = marker, next = ArcTool(points))
        }
        val arc = ArcFromThreePoints.of(points[0], points[1], points[2], ElementBase(ctx.ids.next(), style = ctx.style))
            ?: return ToolResult(preview = null, next = ArcTool(), notice = ToolNotice.NothingDrawn)
        return ToolResult(AddElementCommand(arc), null, ArcTool())
    }

    private class Pressed(private val owner: ArcTool, private val pointerId: Int) : Tool {
        override val id: ToolId get() = ToolId.Arc

        override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = when {
            event.type == InputType.Escape -> ToolResult(preview = null, next = ArcTool())
            event.type == InputType.Cancel -> ToolResult(preview = null, next = owner)
            event.type == InputType.Down && event.pointerId != pointerId -> ToolResult(preview = null, next = owner)
            event.pointerId != pointerId -> ToolResult.Nothing
            event.type == InputType.Up -> owner.place(event.point, ctx)
            else -> ToolResult.Nothing
        }
    }

    private companion object {
        const val ARC_POINTS = 3
    }
}
