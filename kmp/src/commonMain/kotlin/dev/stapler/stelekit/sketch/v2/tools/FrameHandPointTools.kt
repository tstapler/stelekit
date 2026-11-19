// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import kotlin.math.abs
import kotlin.math.min

/** The id every transient preview element carries; previews are never stored, so one fixed id is enough. */
internal val PREVIEW_ID = ElementId("preview")

internal fun unitOrZero(v: Double): SceneUnit = SceneUnit.of(v).getOrNull() ?: SceneUnit.ZERO

/**
 * Frame tool (Story 3.2.3, Task 3.2.3b): drag a frame; elements fully inside it when released become its members in
 * the same undo step. A frame smaller than [MIN_SIDE] units on a side is a stray click and commits nothing.
 */
class FrameTool : DragGestureTool() {
    override val id: ToolId = ToolId.Frame

    override fun idle(): Tool = this

    private fun frame(start: ScenePoint, end: ScenePoint, base: ElementBase): FrameElement = FrameElement(
        base,
        unitOrZero(min(start.x.value, end.x.value)), unitOrZero(min(start.y.value, end.y.value)),
        unitOrZero(abs(end.x.value - start.x.value)), unitOrZero(abs(end.y.value - start.y.value)),
    )

    override fun previewFor(start: ScenePoint, current: ScenePoint, event: InputEvent, ctx: ToolContext): ToolPreview =
        ToolPreview(listOf(frame(start, current, ElementBase(PREVIEW_ID, style = ctx.style))))

    override fun commandFor(start: ScenePoint, end: ScenePoint, event: InputEvent, ctx: ToolContext): ToolCommand? {
        val f = frame(start, end, ElementBase(ctx.ids.next(), style = ctx.style))
        if (f.width.value < MIN_SIDE || f.height.value < MIN_SIDE) return null
        val area = Bounds(f.x.value, f.y.value, f.x.value + f.width.value, f.y.value + f.height.value)
        return AddFrameCommand(f, FrameMembership.adoptedBy(ctx.scene, area))
    }

    private companion object {
        const val MIN_SIDE = 2.0
    }
}

/**
 * Hand tool (Story 3.2.3): its drag pans the view, which is the pointer adapter's job (it owns the viewport and
 * routes the drag to a pan while this tool is active), so the tool itself never commits anything.
 */
class HandTool : Tool {
    override val id: ToolId = ToolId.Hand

    override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = ToolResult.Nothing
}

/**
 * Point tool (Story 3.2.4a, OD-8): a click (or a Crosshair placement, which is the same Down and Up) puts one free
 * [PointElement] at the release position. The Coincident that a snap to a line endpoint adds in the same transaction
 * arrives with the constraint story (4.2.2).
 */
class PointTool : DragGestureTool() {
    override val id: ToolId = ToolId.Point

    override fun idle(): Tool = this

    private fun point(at: ScenePoint, base: ElementBase) = PointElement(base, at.x, at.y)

    override fun previewFor(start: ScenePoint, current: ScenePoint, event: InputEvent, ctx: ToolContext): ToolPreview =
        ToolPreview(listOf(point(current, ElementBase(PREVIEW_ID, style = ctx.style))))

    override fun commandFor(start: ScenePoint, end: ScenePoint, event: InputEvent, ctx: ToolContext): ToolCommand =
        AddElementCommand(point(end, ElementBase(ctx.ids.next(), style = ctx.style)))
}
