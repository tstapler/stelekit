// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.tools.ArcTool
import dev.stapler.stelekit.sketch.v2.tools.ArrowTool
import dev.stapler.stelekit.sketch.v2.tools.InputEvent
import dev.stapler.stelekit.sketch.v2.tools.InputType
import dev.stapler.stelekit.sketch.v2.tools.LineTool
import dev.stapler.stelekit.sketch.v2.tools.TextTool
import dev.stapler.stelekit.sketch.v2.tools.Tool
import dev.stapler.stelekit.sketch.v2.tools.ToolCommand
import dev.stapler.stelekit.sketch.v2.tools.ToolContext
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.tools.ToolNotice
import dev.stapler.stelekit.sketch.v2.tools.ToolPreview
import dev.stapler.stelekit.sketch.v2.tools.ToolResult
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import kotlin.math.roundToInt

/** How a drawing tool takes its points from the Crosshair (ux.md section 10: "Text, Frame, Point and Arc follow the same place-by-Enter rule"). */
enum class PlaceRule {
    /** Enter places corner 1, the arrow keys move, Enter places corner 2 and commits (Rectangle, Diamond, Ellipse, Frame). */
    CornerToCorner,

    /** Each Enter places one point; the tool decides when it is done (Line, Arrow, Point, Arc, Text). */
    PointPerEnter,
}

/** Which tools the keyboard can draw with. Freedraw is the recorded WCAG 2.5.7 exemption; Select, Hand, Eraser and Image do not place points. */
object KeyboardDrawingPolicy {
    fun ruleOf(tool: ToolId): PlaceRule? = when (tool) {
        ToolId.Rectangle, ToolId.Diamond, ToolId.Ellipse, ToolId.Frame -> PlaceRule.CornerToCorner
        ToolId.Line, ToolId.Arrow, ToolId.Point, ToolId.Arc, ToolId.Text -> PlaceRule.PointPerEnter
        ToolId.Select, ToolId.Freedraw, ToolId.Image, ToolId.Eraser, ToolId.Hand -> null
    }

    /** The tools with a keyboard path. */
    val tools: List<ToolId> get() = ToolId.entries.filter { ruleOf(it) != null }

    /** The tool with no keyboard path, because its stroke is path-dependent (WCAG 2.5.7); the shortcuts sheet says so. */
    val exempt: ToolId = ToolId.Freedraw
}

/** A key the canvas hands to Crosshair mode. */
sealed interface CrosshairKey {
    /** An arrow key: [dx] and [dy] are -1, 0 or 1; [far] is Shift (ten units). */
    data class Move(val dx: Int, val dy: Int, val far: Boolean) : CrosshairKey

    data object Enter : CrosshairKey

    data object Escape : CrosshairKey
}

/**
 * What one key did. [session] is the Crosshair after it, null once Crosshair mode ended. [escRow] is the row of the one Esc
 * order (ux.md section 17) that an Escape press took; [announcement] is the live-region text, null when the host says it
 * itself (a committed element is announced with its name, see [CrosshairDrawing.announceAdded]).
 */
data class CrosshairStep(
    val session: CrosshairDrawing?,
    val command: ToolCommand? = null,
    val preview: ToolPreview? = null,
    val notice: ToolNotice? = null,
    val announcement: String? = null,
    val escRow: EscRow? = null,
)

/**
 * Keyboard-only drawing through Crosshair mode (Task 3.6.3c, UX-107, UX-41, UX-105; WCAG 2.1.1). It drives the active tool
 * with the same events a pointer would send, taken from the crosshair: arrow keys move it (Shift ten units), Enter places a
 * point (a [PlaceRule] says what that means for the tool) and Esc backs out one row of the Esc order: mid-draw (row 6), then
 * Crosshair itself (row 7). A text overlay commits on Esc (row 3, D-100). Pure and immutable; the host turns
 * [CrosshairStep.command] into a command and runs it as one undo step.
 */
class CrosshairDrawing private constructor(
    private val rule: PlaceRule,
    val tool: Tool,
    val crosshair: ScenePoint,
    private val armed: Boolean,
) {
    val toolId: ToolId get() = tool.id

    /** True while a text overlay is open for the placed point. */
    val editingText: Boolean get() = tool is TextTool.Editing

    /** True while a gesture is in progress: corner 1 is placed, or a line, arrow or arc has points. */
    val midDraw: Boolean
        get() = when (tool) {
            is LineTool -> tool.placedPoints.isNotEmpty()
            is ArrowTool -> tool.placedPoints.isNotEmpty()
            is ArcTool -> tool.placedPoints.isNotEmpty()
            else -> armed
        }

    /** The rows of the Esc order this Crosshair holds, for the host to merge with its own rows. */
    fun escRows(): Set<EscRow> = buildSet {
        if (editingText) add(EscRow.TextOverlayOrDimensionField)
        if (midDraw) add(EscRow.MidDraw)
        add(EscRow.Crosshair)
    }

    /** Handles [key]; [timestamp] is the monotonic clock the tools use to tell a double press from two. */
    fun press(key: CrosshairKey, ctx: ToolContext, timestamp: Long): CrosshairStep = when (key) {
        is CrosshairKey.Move -> move(key, ctx, timestamp)
        CrosshairKey.Enter -> if (editingText) CrosshairStep(this) else enter(ctx, timestamp)
        CrosshairKey.Escape -> escape(ctx, timestamp)
    }

    private fun move(key: CrosshairKey.Move, ctx: ToolContext, timestamp: Long): CrosshairStep {
        val step = if (key.far) FAR_UNITS else NEAR_UNITS
        val to = shifted(crosshair, key.dx * step, key.dy * step, ctx)
        val moved = CrosshairDrawing(rule, tool, to, armed)
        if (!midDraw || editingText) return CrosshairStep(moved, announcement = SketchStrings.KeyboardDrawing.crosshairAt(to.x.value.roundToInt(), to.y.value.roundToInt()))
        val result = tool.onEvent(event(InputType.Move, to, timestamp), ctx)
        return CrosshairStep(CrosshairDrawing(rule, result.next ?: tool, to, armed), preview = result.preview)
    }

    private fun enter(ctx: ToolContext, timestamp: Long): CrosshairStep = when (rule) {
        PlaceRule.CornerToCorner -> if (armed) finishCorner(ctx, timestamp) else startCorner(ctx, timestamp)
        PlaceRule.PointPerEnter -> placePoint(ctx, timestamp)
    }

    private fun startCorner(ctx: ToolContext, timestamp: Long): CrosshairStep {
        val result = tool.onEvent(event(InputType.Down, crosshair, timestamp), ctx)
        val announcement = SketchStrings.KeyboardDrawing.cornerPlaced(crosshair.x.value.roundToInt(), crosshair.y.value.roundToInt())
        return CrosshairStep(CrosshairDrawing(rule, result.next ?: tool, crosshair, armed = true), announcement = announcement)
    }

    private fun finishCorner(ctx: ToolContext, timestamp: Long): CrosshairStep {
        val result = tool.onEvent(event(InputType.Up, crosshair, timestamp), ctx)
        return settled(result)
    }

    private fun placePoint(ctx: ToolContext, timestamp: Long): CrosshairStep {
        val down = tool.onEvent(event(InputType.Down, crosshair, timestamp), ctx)
        val pressed = down.next ?: tool
        val up = pressed.onEvent(event(InputType.Up, crosshair, timestamp), ctx)
        val merged = ToolResult(command = up.command ?: down.command, preview = up.preview, next = up.next ?: pressed, notice = up.notice ?: down.notice)
        return settled(merged)
    }

    private fun settled(result: ToolResult): CrosshairStep {
        val next = CrosshairDrawing(rule, result.next ?: tool, crosshair, armed = false)
        val announcement = when {
            result.command != null || next.editingText -> null
            result.notice == ToolNotice.NothingDrawn -> SketchStrings.KeyboardDrawing.NOTHING_DRAWN
            next.midDraw -> SketchStrings.KeyboardDrawing.pointPlaced(placedCount(next.tool), crosshair.x.value.roundToInt(), crosshair.y.value.roundToInt())
            else -> null
        }
        return CrosshairStep(next, result.command, result.preview, result.notice, announcement)
    }

    private fun placedCount(t: Tool): Int = when (t) {
        is LineTool -> t.placedPoints.size
        is ArrowTool -> t.placedPoints.size
        is ArcTool -> t.placedPoints.size
        else -> 1
    }

    private fun escape(ctx: ToolContext, timestamp: Long): CrosshairStep {
        val result = tool.onEvent(event(InputType.Escape, crosshair, timestamp), ctx)
        return when {
            editingText -> CrosshairStep(CrosshairDrawing(rule, result.next ?: tool, crosshair, armed = false), result.command, escRow = EscRow.TextOverlayOrDimensionField)
            midDraw -> CrosshairStep(
                CrosshairDrawing(rule, result.next ?: tool, crosshair, armed = false),
                announcement = SketchStrings.KeyboardDrawing.CANCELLED,
                escRow = EscRow.MidDraw,
            )
            else -> CrosshairStep(null, announcement = SketchStrings.KeyboardDrawing.ENDED, escRow = EscRow.Crosshair)
        }
    }

    private fun event(type: InputType, at: ScenePoint, timestamp: Long) = InputEvent(type, at, timestamp = timestamp)

    private fun shifted(from: ScenePoint, dx: Double, dy: Double, ctx: ToolContext): ScenePoint {
        val x = SceneUnit.of(from.x.value + dx, ctx.scene.limits).getOrNull() ?: from.x
        val y = SceneUnit.of(from.y.value + dy, ctx.scene.limits).getOrNull() ?: from.y
        return ScenePoint(x, y)
    }

    companion object {
        /** One arrow press moves the crosshair this many scene units; Shift moves it ten. */
        const val NEAR_UNITS = 1.0
        const val FAR_UNITS = 10.0

        /**
         * Starts Crosshair mode with [tool] (a fresh Idle tool) at [at], the canvas centre or the last pointer position;
         * null when the tool has no keyboard path ([KeyboardDrawingPolicy]).
         */
        fun begin(tool: Tool, at: ScenePoint): CrosshairDrawing? =
            KeyboardDrawingPolicy.ruleOf(tool.id)?.let { CrosshairDrawing(it, tool, at, armed = false) }

        /** "Added Rectangle 3": what the live region says once the host has applied the add command and the element has its name. */
        fun announceAdded(element: SceneElement): String = SketchStrings.KeyboardDrawing.added(ElementNames.nameOf(element))
    }
}
