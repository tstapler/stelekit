// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.binding.BindTarget
import dev.stapler.stelekit.sketch.v2.binding.BindTargets
import dev.stapler.stelekit.sketch.v2.binding.BindingGeometry
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowType
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.withArrowType

/**
 * What a new arrow looks like and whether its ends bind (Story 3.3.1). [bind] false is the "Don't bind" chip of the action
 * chip row; Ctrl (Cmd on macOS) does the same for one drop. [angleSnap] is the "15 degrees" chip, as for the line.
 */
data class ArrowOptions(
    val type: ArrowType = ArrowType.Sharp,
    val startArrowhead: Arrowhead = Arrowhead.None,
    val endArrowhead: Arrowhead = Arrowhead.Arrow,
    val angleSnap: Boolean = false,
    val bind: Boolean = true,
)

/**
 * Arrow tool (Story 3.3.1, Task 3.3.1a): the line tool's gestures (drag for two points, click points, a double click or
 * Done to finish, Esc or a second pointer to abandon), committing an [ArrowElement] whose style follows [ArrowOptions.type]:
 * Round carries a roundness (a curve through three or more points), Elbow sets the stored flag and draws as a polyline
 * (OD-7). When an end is dropped on or within `BindTargets.SNAP_RADIUS_PX` of a shape the end is bound to it (Orbit) and
 * snapped to its outline; Ctrl or the chip disables that. The preview names the shape an end would bind to.
 *
 * Immutable like [LineTool], which owns the gesture state.
 */
class ArrowTool private constructor(
    private val options: ArrowOptions,
    private val line: LineTool,
) : Tool {
    constructor(options: ArrowOptions = ArrowOptions()) : this(options, LineTool(LineOptions(options.angleSnap)))

    override val id: ToolId = ToolId.Arrow

    /** The vertices placed so far, for the host's status line and Done chip. */
    val placedPoints: List<ScenePoint> get() = line.placedPoints

    /** Finishes the arrow with what was placed (the Done chip and Enter). */
    fun done(ctx: ToolContext, timestamp: Long = 0L): ToolResult =
        convert(line.done(ctx, timestamp), ctx, dropFor(options.bind)).flagIfNothingDrawn()

    override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult {
        val drop = dropFor(options.bind && !event.modifiers.ctrl && !event.modifiers.meta)
        val converted = convert(line.onEvent(event, ctx), ctx, drop)
        return if (event.type == InputType.Up) converted.flagIfNothingDrawn() else converted
    }

    /** A finish with one vertex placed commits nothing; the host says "Nothing drawn" (ux.md section 5, zero-length arrow). */
    private fun ToolResult.flagIfNothingDrawn(): ToolResult {
        val finishedEmpty = command == null && (next as? ArrowTool)?.placedPoints?.isEmpty() == true
        return if (finishedEmpty && placedPoints.isNotEmpty()) copy(notice = ToolNotice.NothingDrawn) else this
    }

    private fun convert(result: ToolResult, ctx: ToolContext, drop: Drop): ToolResult = ToolResult(
        command = result.command?.let { arrowCommand(it, ctx, drop) },
        preview = result.preview?.let { arrowPreview(it, ctx, drop) },
        next = (result.next as? LineTool)?.let { ArrowTool(options, it) },
        notice = result.notice,
    )

    private fun arrowCommand(command: ToolCommand, ctx: ToolContext, drop: Drop): ToolCommand {
        val drawn = (command as? AddElementCommand)?.element as? LineElement ?: return command
        return AddElementCommand(arrowFrom(drawn, ctx, drop).element)
    }

    private fun arrowPreview(preview: ToolPreview, ctx: ToolContext, drop: Drop): ToolPreview {
        val drawn = preview.elements.singleOrNull() as? LineElement ?: return preview
        val built = arrowFrom(drawn, ctx, drop)
        return preview.copy(elements = listOf(built.element), highlight = built.highlight)
    }

    /** Whether the ends of this drop may bind. */
    private enum class Drop { Bind, Free }

    private fun dropFor(bind: Boolean) = if (bind) Drop.Bind else Drop.Free

    private class Built(val element: ArrowElement, val highlight: ElementId?)

    private fun arrowFrom(drawn: LineElement, ctx: ToolContext, drop: Drop): Built {
        val raw = ArrowElement(
            drawn.base, drawn.points,
            startArrowhead = options.startArrowhead, endArrowhead = options.endArrowhead,
        ).withArrowType(options.type)
        if (drop == Drop.Free) return Built(raw, null)
        val start = targetAt(drawn.points.first(), ctx)
        val end = targetAt(drawn.points.last(), ctx)
        val snapped = snapEnds(drawn.points, start, end)
        if (snapped.size == drawn.points.size && collapsed(snapped)) return Built(raw, end?.target?.id ?: start?.target?.id)
        val bound = raw.copy(points = snapped, startBinding = start?.binding, endBinding = end?.binding)
        return Built(bound, end?.target?.id ?: start?.target?.id)
    }

    private fun targetAt(p: ScenePoint, ctx: ToolContext): BindTarget? =
        BindTargets.find(ctx.scene, ctx.spatialIndex, p.x.value, p.y.value, ctx.zoom)

    private fun snapEnds(points: List<ScenePoint>, start: BindTarget?, end: BindTarget?): List<ScenePoint> {
        val out = points.toMutableList()
        start?.let { endPoint(it.target, it.binding)?.let { p -> out[0] = p } }
        end?.let { endPoint(it.target, it.binding)?.let { p -> out[out.lastIndex] = p } }
        return out
    }

    private fun endPoint(target: SceneElement, binding: Binding): ScenePoint? =
        BindingGeometry.pointOf(target, binding)?.let { (x, y) -> ScenePoint(unitOrZero(x), unitOrZero(y)) }

    /** Both ends snapped onto the same outline point: a zero-length arrow, so the drop stays unbound. */
    private fun collapsed(points: List<ScenePoint>): Boolean = points.size == MIN_POINTS && points.first() == points.last()

    private companion object {
        const val MIN_POINTS = 2
    }
}
