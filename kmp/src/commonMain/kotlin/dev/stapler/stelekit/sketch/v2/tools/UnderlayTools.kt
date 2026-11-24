// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.UnderlayGesture
import dev.stapler.stelekit.sketch.v2.scene.UnderlayIds
import dev.stapler.stelekit.sketch.v2.scene.UnderlayPlacement
import dev.stapler.stelekit.sketch.v2.scene.underlay
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Marks a tool that wants every touch pointer, not only the first: the pointer adapter then forwards both fingers instead of navigating. */
interface MultiPointerTool

/** The calibration points are placed (both set, and again after each drag): the host opens the length field for this pair. */
data class CalibrationPointsCommand(val a: ScenePoint, val b: ScenePoint) : ToolCommand

/** A finished adjust of the underlay: one undo step. */
data class AdjustUnderlayCommand(val placement: UnderlayPlacement) : ToolCommand

/** The underlay mode ended (Esc, or nothing left to do): the host clears its calibration state. */
data object EndUnderlayModeCommand : ToolCommand

private fun distance(a: ScenePoint, b: ScenePoint): Double = hypot(a.x.value - b.x.value, a.y.value - b.y.value)

/**
 * Two-point calibration (epic E5): press to drop point A, drag to place it with the loupe, lift; the same for B. A press near a
 * placed point picks it up again. Each lift with both points placed asks the host for the real distance. The tool never edits the
 * photo: calibrating is the host's [dev.stapler.stelekit.sketch.v2.commands.CalibrateUnderlay] once a length is typed.
 */
class UnderlayCalibrateTool private constructor(
    private val points: List<ScenePoint>,
    private val dragging: Int?,
    private val pointerId: Int,
    /** The points as they were when the current drag began, restored if a second finger turns the drag into a pinch. */
    private val before: List<ScenePoint> = emptyList(),
) : Tool {
    constructor() : this(emptyList(), null, 0)

    override val id: ToolId = ToolId.Select

    override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = when {
        event.type == InputType.Escape -> ToolResult(command = EndUnderlayModeCommand, next = SelectTool())
        event.type == InputType.Cancel -> ToolResult(preview = previewOf(points, null, ctx), next = UnderlayCalibrateTool(points, null, 0))
        dragging == null -> if (event.type == InputType.Down) onDown(event, ctx) else ToolResult.Nothing
        event.type == InputType.Down && event.pointerId != pointerId -> ToolResult(preview = previewOf(before, null, ctx), next = UnderlayCalibrateTool(before, null, 0))
        event.pointerId != pointerId -> ToolResult.Nothing
        event.type == InputType.Move -> moveTo(event, ctx)
        event.type == InputType.Up -> release(event, ctx)
        else -> ToolResult.Nothing
    }

    private fun onDown(event: InputEvent, ctx: ToolContext): ToolResult {
        val reach = GRAB_DP * ctx.pxPerDp / ctx.zoom
        val near = points.withIndex().filter { distance(it.value, event.point) <= reach }.minByOrNull { distance(it.value, event.point) }?.index
        val index = near ?: points.size.takeIf { it < POINT_COUNT } ?: return ToolResult.Nothing
        val next = if (near == null) points + event.point else points.mapIndexed { i, p -> if (i == index) event.point else p }
        return ToolResult(preview = previewOf(next, index, ctx), next = UnderlayCalibrateTool(next, index, event.pointerId, points))
    }

    private fun moveTo(event: InputEvent, ctx: ToolContext): ToolResult {
        val index = dragging ?: return ToolResult.Nothing
        val next = points.mapIndexed { i, p -> if (i == index) event.point else p }
        return ToolResult(preview = previewOf(next, index, ctx), next = UnderlayCalibrateTool(next, index, pointerId, before))
    }

    private fun release(event: InputEvent, ctx: ToolContext): ToolResult {
        val index = dragging ?: return ToolResult.Nothing
        val next = points.mapIndexed { i, p -> if (i == index) event.point else p }
        val ready = next.size == POINT_COUNT
        return ToolResult(
            command = if (ready) CalibrationPointsCommand(next[0], next[1]) else null,
            preview = previewOf(next, null, ctx),
            next = UnderlayCalibrateTool(next, null, 0),
        )
    }

    private fun previewOf(placed: List<ScenePoint>, active: Int?, ctx: ToolContext): ToolPreview {
        val unitsPerPx = ctx.pxPerDp / ctx.zoom
        val style = ElementStyle(strokeColor = MARK_COLOR, backgroundColor = MARK_FILL, fillStyle = FillStyle.Solid, strokeWidth = STROKE_DP * unitsPerPx, roughness = 0)
        val radius = SceneUnit.of(MARK_DP * unitsPerPx).getOrNull() ?: SceneUnit.ofInt(1)
        val marks = placed.mapIndexed { i, p ->
            EllipseElement(ElementBase(ElementId("underlay.cal.$i"), style = style), p.x, p.y, radius, radius)
        }
        val line = if (placed.size == POINT_COUNT) listOf(LineElement(ElementBase(ElementId("underlay.cal.line"), style = style.copy(backgroundColor = "transparent")), placed)) else emptyList()
        return ToolPreview(line + marks, cursor = active?.let { placed.getOrNull(it) })
    }

    companion object {
        private const val POINT_COUNT = 2
        private const val GRAB_DP = 24.0
        private const val MARK_DP = 7.0
        private const val STROKE_DP = 2.0
        private const val MARK_COLOR = "#e8590c"
        private const val MARK_FILL = "#ffffff80"
    }
}

/**
 * Moves, turns and (for an uncalibrated photo) scales the underlay with one or two pointers (epic E5). The photo shows its new
 * placement as a preview while the gesture runs, and one [AdjustUnderlayCommand] commits it when the last pointer lifts. The
 * loupe follows the first pointer, so a precise placement of a photo detail under a drawn point works as with any tool.
 */
class UnderlayAdjustTool private constructor(
    private val live: UnderlayPlacement?,
    private val base: UnderlayPlacement?,
    private val from: Map<Int, ScenePoint>,
    private val now: Map<Int, ScenePoint>,
) : Tool, MultiPointerTool {
    constructor() : this(null, null, emptyMap(), emptyMap())

    override val id: ToolId = ToolId.Select

    override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = when (event.type) {
        InputType.Escape -> ToolResult(command = EndUnderlayModeCommand, next = SelectTool())
        InputType.Cancel -> ToolResult(preview = null, next = UnderlayAdjustTool())
        InputType.Down -> onDown(event, ctx)
        InputType.Move -> onMove(event, ctx)
        InputType.Up -> onUp(event, ctx)
    }

    private fun onDown(event: InputEvent, ctx: ToolContext): ToolResult {
        val start = live ?: ctx.scene.underlay?.placement ?: return ToolResult.Nothing
        if (now.size >= MAX_POINTERS || event.pointerId in now) return ToolResult.Nothing
        val positions = now + (event.pointerId to event.point)
        return ToolResult(preview = previewOf(start, positions, ctx), next = UnderlayAdjustTool(start, start, positions, positions))
    }

    private fun onMove(event: InputEvent, ctx: ToolContext): ToolResult {
        val start = base ?: return ToolResult.Nothing
        if (event.pointerId !in now) return ToolResult.Nothing
        val positions = now + (event.pointerId to event.point)
        val placement = place(start, positions, ctx)
        return ToolResult(preview = previewOf(placement, positions, ctx), next = UnderlayAdjustTool(placement, start, from, positions))
    }

    private fun onUp(event: InputEvent, ctx: ToolContext): ToolResult {
        if (event.pointerId !in now) return ToolResult.Nothing
        val current = live ?: return ToolResult.Nothing
        val remaining = now - event.pointerId
        if (remaining.isNotEmpty()) {
            return ToolResult(preview = previewOf(current, remaining, ctx), next = UnderlayAdjustTool(current, current, remaining, remaining))
        }
        val moved = current != ctx.scene.underlay?.placement
        return ToolResult(command = if (moved) AdjustUnderlayCommand(current) else null, preview = null, next = UnderlayAdjustTool())
    }

    private fun place(start: UnderlayPlacement, positions: Map<Int, ScenePoint>, ctx: ToolContext): UnderlayPlacement {
        val ids = from.keys.sorted()
        val scale = ctx.scene.underlay?.calibrated != true
        return UnderlayGesture.transform(
            start,
            ids.map { from.getValue(it).let { p -> p.x.value to p.y.value } },
            ids.map { (positions[it] ?: from.getValue(it)).let { p -> p.x.value to p.y.value } },
            scale,
            MIN_SIDE_UNITS..SceneLimits.MAX_COORD / SIDE_DIVISOR,
        )
    }

    private fun previewOf(placement: UnderlayPlacement, positions: Map<Int, ScenePoint>, ctx: ToolContext): ToolPreview? {
        val element = ctx.scene[UnderlayIds.ELEMENT] as? ImageElement ?: return null
        val shown = element.copy(
            x = SceneUnit.of(placement.x).getOrNull() ?: element.x,
            y = SceneUnit.of(placement.y).getOrNull() ?: element.y,
            width = SceneUnit.of(placement.width).getOrNull() ?: element.width,
            height = SceneUnit.of(placement.height).getOrNull() ?: element.height,
            angle = placement.angle,
            // The preview is drawn on its own, outside the layer, so the layer's opacity is folded into the element's.
            base = element.base.copy(layerId = LayerId.Default, opacity = ((ctx.scene.underlay?.layer?.opacity ?: 1.0) * ElementBase.FULL_OPACITY).roundToInt()),
        )
        return ToolPreview(listOf(shown), cursor = positions.entries.minByOrNull { it.key }?.value)
    }

    companion object {
        private const val MAX_POINTERS = 2

        /** Smallest long side a pinch may shrink the photo to: one inch. */
        const val MIN_SIDE_UNITS = 16.0
        private const val SIDE_DIVISOR = 4.0
    }
}
