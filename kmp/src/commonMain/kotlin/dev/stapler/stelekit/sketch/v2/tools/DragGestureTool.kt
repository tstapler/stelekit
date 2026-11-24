// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.ScenePoint

/**
 * Press-drag-release tools (Rectangle, Diamond, Ellipse, Arrow, Line, Frame, Point and Arc share it) with the
 * cancel rules in one place (UX-08): a second pointer going down, Esc or a platform cancel abandons the gesture
 * with no command, no undo entry and a cleared preview, and the tool returns to Idle.
 *
 * Pointer positions are first locked onto the drawing's points ([PointSnapper]) when the snap settings ask for it.
 *
 * Subclasses supply [previewFor] (the shape between press and the current point) and [commandFor] (what to commit
 * on release); they never see a cancelled gesture.
 */
abstract class DragGestureTool : Tool {
    /** The Idle state of this tool (what a cancelled or finished gesture returns to). */
    protected abstract fun idle(): Tool

    protected abstract fun previewFor(start: ScenePoint, current: ScenePoint, event: InputEvent, ctx: ToolContext): ToolPreview?

    protected abstract fun commandFor(start: ScenePoint, end: ScenePoint, event: InputEvent, ctx: ToolContext): ToolCommand?

    override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult {
        if (event.type != InputType.Down) return ToolResult.Nothing
        val placed = PointSnapper.place(event.point, null, event, ctx)
        return ToolResult(preview = placed.markerOnly(), next = Dragging(this, event.pointerId, placed.point))
    }

    private class Dragging(
        private val owner: DragGestureTool,
        private val pointerId: Int,
        private val start: ScenePoint,
    ) : Tool {
        override val id: ToolId get() = owner.id

        override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = when {
            event.type == InputType.Escape || event.type == InputType.Cancel -> cancel()
            event.type == InputType.Down && event.pointerId != pointerId -> cancel()
            event.pointerId != pointerId -> ToolResult.Nothing
            event.type == InputType.Move -> PointSnapper.place(event.point, null, event, ctx).let { at ->
                ToolResult(preview = owner.previewFor(start, at.point, event, ctx)?.let(at::marked))
            }
            event.type == InputType.Up -> ToolResult(owner.commandFor(start, PointSnapper.place(event.point, null, event, ctx).point, event, ctx), null, owner.idle())
            else -> ToolResult.Nothing
        }

        private fun cancel() = ToolResult(preview = null, next = owner.idle())
    }
}
