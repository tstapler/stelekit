// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** The "15 degrees" chip of the action chip row; Shift does the same for a keyboard or mouse user. */
data class LineOptions(val angleSnap: Boolean = false)

/** Snaps a segment to 15 degree steps about its start (Story 3.2.1: "line with Shift snaps to 15 degree steps"). */
internal object AngleSnap {
    private const val STEP = PI / 12.0

    fun snap(from: ScenePoint, to: ScenePoint): ScenePoint {
        val dx = to.x.value - from.x.value
        val dy = to.y.value - from.y.value
        val r = hypot(dx, dy)
        if (r == 0.0) return to
        val a = (atan2(dy, dx) / STEP).roundToInt() * STEP
        return ScenePoint(unitOrZero(from.x.value + r * cos(a)), unitOrZero(from.y.value + r * sin(a)))
    }
}

/**
 * Line and polyline tool (Story 3.2.1, Task 3.2.1b). Clicks add vertices; clicking the last vertex again (a double
 * click, or Enter twice in Crosshair mode) or the Done chip ([done]) finishes the line. A press-drag-release with no vertex
 * yet draws a two-point line in one gesture. A second pointer, Esc or a platform cancel abandons the line with no
 * command (UX-08). The line stops growing at `SceneLimits.maxPointsPerElement` vertices and finishes itself there.
 *
 * Immutable: each event returns the next state in [ToolResult.next].
 */
class LineTool private constructor(
    private val options: LineOptions,
    private val points: List<ScenePoint>,
    private val press: Press?,
    private val finished: Finished?,
) : Tool {
    constructor(options: LineOptions = LineOptions()) : this(options, emptyList(), null, null)

    override val id: ToolId = ToolId.Line

    /** The vertices placed so far, for the host's status line and Done chip. */
    val placedPoints: List<ScenePoint> get() = points

    private class Press(val pointerId: Int, val at: ScenePoint)

    /** Where and when the last line finished, so the second click of a double click starts nothing. */
    private class Finished(val at: ScenePoint, val timestamp: Long)

    private fun idle(finish: Finished? = null) = LineTool(options, emptyList(), null, finish)

    /**
     * [to] locked onto the drawing's points when one is in reach (a feature of the drawing beats the 15 degree step: the
     * user pointed at it), else onto the angle step about [from] when that is on, else onto the grid when that is on.
     */
    private fun snapped(from: ScenePoint?, to: ScenePoint, event: InputEvent, ctx: ToolContext): PointSnapper.Placed {
        val placed = PointSnapper.place(to, from, event, ctx)
        val stepping = options.angleSnap || event.modifiers.shift
        if (placed.hit != null || from == null || !stepping) return placed
        return PointSnapper.Placed(AngleSnap.snap(from, to), null)
    }

    /** Finishes the line with what was placed (the Done chip and Enter); nothing is committed with fewer than two vertices. */
    fun done(ctx: ToolContext, timestamp: Long = 0L): ToolResult = finish(points, ctx, timestamp)

    override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = when {
        event.type == InputType.Escape || event.type == InputType.Cancel -> abandon()
        event.type == InputType.Down -> onDown(event, ctx)
        press == null -> if (event.type == InputType.Move && points.isNotEmpty()) ToolResult(preview = previewTo(event, ctx)) else ToolResult.Nothing
        event.pointerId != press.pointerId -> ToolResult.Nothing
        event.type == InputType.Move -> ToolResult(preview = previewTo(event, ctx))
        event.type == InputType.Up -> onUp(event, ctx)
        else -> ToolResult.Nothing
    }

    private fun abandon(): ToolResult =
        if (points.isEmpty() && press == null) ToolResult.Nothing else ToolResult(preview = null, next = idle())

    private fun onDown(event: InputEvent, ctx: ToolContext): ToolResult = when {
        press != null && event.pointerId != press.pointerId -> ToolResult(preview = null, next = idle())
        isSecondClickOfFinish(event, ctx) -> ToolResult.Nothing
        else -> ToolResult(next = LineTool(options, points, Press(event.pointerId, event.point), finished))
    }

    private fun isSecondClickOfFinish(event: InputEvent, ctx: ToolContext): Boolean {
        val f = finished ?: return false
        return event.timestamp - f.timestamp <= DOUBLE_CLICK_MS && near(f.at, event.point, ctx)
    }

    private fun near(a: ScenePoint, b: ScenePoint, ctx: ToolContext): Boolean =
        hypot(a.x.value - b.x.value, a.y.value - b.y.value) * ctx.zoom <= SelectTool.DRAG_THRESHOLD_PX

    private fun previewTo(event: InputEvent, ctx: ToolContext): ToolPreview? {
        val from = points.lastOrNull() ?: press?.let { snapped(null, it.at, event, ctx).point }
        val placed = snapped(from, event.point, event, ctx)
        val shown = if (points.isEmpty()) listOf(from ?: return null, placed.point) else points + placed.point
        return line(shown, ElementBase(PREVIEW_ID, style = ctx.style))?.let { placed.marked(ToolPreview(listOf(it))) }
    }

    private fun onUp(event: InputEvent, ctx: ToolContext): ToolResult {
        val down = checkNotNull(press) { "an Up is only handled with a press" }.at
        val dragged = !near(down, event.point, ctx)
        return when {
            points.isEmpty() && dragged -> {
                val start = snapped(null, down, event, ctx).point
                finish(listOf(start, snapped(start, event.point, event, ctx).point), ctx, event.timestamp)
            }
            points.isNotEmpty() && near(points.last(), down, ctx) -> finish(points, ctx, event.timestamp)
            else -> addVertex(snapped(points.lastOrNull(), down, event, ctx).point, ctx, event)
        }
    }

    private fun addVertex(at: ScenePoint, ctx: ToolContext, event: InputEvent): ToolResult {
        val next = points + at
        if (next.size >= SceneLimits.Default.maxPointsPerElement) return finish(next, ctx, event.timestamp)
        return ToolResult(preview = null, next = LineTool(options, next, null, null))
    }

    private fun finish(vertices: List<ScenePoint>, ctx: ToolContext, timestamp: Long): ToolResult {
        val at = vertices.lastOrNull() ?: return ToolResult(preview = null, next = idle())
        val element = line(vertices, ElementBase(ctx.ids.next(), style = ctx.style))
        return ToolResult(element?.let { AddElementCommand(it) }, null, idle(Finished(at, timestamp)))
    }

    /** A line needs two vertices that are not the same place. */
    private fun line(vertices: List<ScenePoint>, base: ElementBase): LineElement? =
        if (vertices.size >= MIN_VERTICES && vertices.distinct().size >= MIN_VERTICES) LineElement(base, vertices) else null

    companion object {
        /** Two clicks on the same spot this close in time are a double click. */
        const val DOUBLE_CLICK_MS = 500L

        private const val MIN_VERTICES = 2
    }
}

/**
 * Point editing mode for a selected line (Task 3.2.1b): press a vertex and drag to move it, press a segment and drag to
 * insert a vertex there, press nothing to leave the mode. One drag is one [SetLinePointsCommand], so one undo step.
 * [removeVertex] is the keyboard and menu path that needs no drag (WCAG 2.5.7). Esc or a second pointer abandons the drag.
 */
class LinePointEditTool private constructor(
    private val lineId: ElementId,
    private val drag: Drag?,
) : Tool {
    constructor(lineId: ElementId) : this(lineId, null)

    override val id: ToolId = ToolId.Select

    private class Drag(val pointerId: Int, val index: Int, val points: List<ScenePoint>)

    private fun lineIn(ctx: ToolContext): LineElement? = ctx.scene[lineId] as? LineElement

    private fun idle() = LinePointEditTool(lineId, null)

    /** The line with vertex [index] deleted, or null when it would leave fewer than two. */
    fun removeVertex(index: Int, ctx: ToolContext): ToolResult {
        val line = lineIn(ctx) ?: return ToolResult.Nothing
        if (index !in line.points.indices || line.points.size <= MIN_VERTICES) return ToolResult.Nothing
        return ToolResult(SetLinePointsCommand(lineId, line.points.filterIndexed { i, _ -> i != index }))
    }

    override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult {
        val line = lineIn(ctx) ?: return ToolResult(next = SelectTool())
        return when {
            event.type == InputType.Escape && drag == null -> ToolResult(next = SelectTool(listOf(lineId)))
            event.type == InputType.Escape || event.type == InputType.Cancel -> ToolResult(preview = null, next = idle())
            drag == null -> if (event.type == InputType.Down) onDown(event, line, ctx) else ToolResult.Nothing
            event.type == InputType.Down -> if (event.pointerId != drag.pointerId) ToolResult(preview = null, next = idle()) else ToolResult.Nothing
            event.pointerId != drag.pointerId -> ToolResult.Nothing
            event.type == InputType.Move -> ToolResult(preview = movedPreview(drag, event, line, ctx))
            event.type == InputType.Up -> onUp(drag, event)
            else -> ToolResult.Nothing
        }
    }

    private fun onDown(event: InputEvent, line: LineElement, ctx: ToolContext): ToolResult {
        val tolerance = HIT_PX / ctx.zoom
        val vertex = line.points.indexOfFirst { dist(it, event.point) <= tolerance }
        if (vertex >= 0) return ToolResult(next = LinePointEditTool(lineId, Drag(event.pointerId, vertex, line.points)))
        val segment = (0 until line.points.size - 1).firstOrNull { distToSegment(event.point, line.points[it], line.points[it + 1]) <= tolerance }
        if (segment == null) return ToolResult(next = SelectTool(listOf(lineId)))
        val inserted = line.points.toMutableList().apply { add(segment + 1, event.point) }
        return ToolResult(next = LinePointEditTool(lineId, Drag(event.pointerId, segment + 1, inserted)))
    }

    private fun moved(drag: Drag, event: InputEvent): List<ScenePoint> {
        val previous = drag.points.getOrNull(drag.index - 1)
        val to = if (event.modifiers.shift && previous != null) AngleSnap.snap(previous, event.point) else event.point
        return drag.points.toMutableList().also { it[drag.index] = to }
    }

    private fun movedPreview(drag: Drag, event: InputEvent, line: LineElement, ctx: ToolContext): ToolPreview {
        val shown = line.copy(base = ElementBase(PREVIEW_ID, style = ctx.style), points = moved(drag, event))
        return ToolPreview(listOf(shown))
    }

    private fun onUp(drag: Drag, event: InputEvent): ToolResult =
        ToolResult(SetLinePointsCommand(lineId, moved(drag, event)), null, idle())

    private fun dist(a: ScenePoint, b: ScenePoint) = hypot(a.x.value - b.x.value, a.y.value - b.y.value)

    private fun distToSegment(p: ScenePoint, a: ScenePoint, b: ScenePoint): Double {
        val dx = b.x.value - a.x.value
        val dy = b.y.value - a.y.value
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((p.x.value - a.x.value) * dx + (p.y.value - a.y.value) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(p.x.value - (a.x.value + t * dx), p.y.value - (a.y.value + t * dy))
    }

    private companion object {
        /** Screen pixels around a vertex or segment that a press grabs. */
        const val HIT_PX = 12.0
        const val MIN_VERTICES = 2
    }
}
