// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An image the user picked and the asset store already holds: its content-hash id and header size in pixels. */
data class PickedImage(val fileId: String, val widthPx: Int, val heightPx: Int) {
    init {
        require(widthPx > 0 && heightPx > 0) { "image size must be positive" }
    }
}

/** A click with no image picked yet: the host opens the platform picker, stores the file, then re-arms the tool with a [PickedImage]. */
data class RequestImagePickCommand(val at: ScenePoint) : ToolCommand

/**
 * Click-to-place image tool (Story 3.4.3). With a [picked] image a click centres it on the click, scaled down (never
 * up) so its longer side is at most [MAX_PLACED_SIDE_UNITS]; the element holds the `fileId`, never the bytes.
 * Without one a click only asks the host to pick a file. A second pointer, Esc or a platform cancel abandons the click and keeps the picked image armed; a placement spends it.
 */
class ImageTool(private val picked: PickedImage? = null) : Tool {
    override val id: ToolId = ToolId.Image

    override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult =
        if (event.type == InputType.Down) ToolResult(next = Pressed(this, event.pointerId)) else ToolResult.Nothing

    private fun commandAt(at: ScenePoint, ctx: ToolContext): ToolCommand {
        val image = picked ?: return RequestImagePickCommand(at)
        val scale = min(1.0, MAX_PLACED_SIDE_UNITS / max(image.widthPx, image.heightPx))
        val w = (image.widthPx * scale).roundToInt().coerceAtLeast(1)
        val h = (image.heightPx * scale).roundToInt().coerceAtLeast(1)
        val x = unit(at.x.value - w / 2.0)
        val y = unit(at.y.value - h / 2.0)
        return AddElementCommand(
            ImageElement(ElementBase(ctx.ids.next(), style = ctx.style), x, y, SceneUnit.ofInt(w), SceneUnit.ofInt(h), fileId = image.fileId),
        )
    }

    private fun unit(v: Double): SceneUnit = SceneUnit.of(v).getOrNull() ?: SceneUnit.ZERO

    private class Pressed(private val owner: ImageTool, private val pointerId: Int) : Tool {
        override val id: ToolId get() = owner.id

        override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = when {
            event.type == InputType.Escape || event.type == InputType.Cancel -> ToolResult(next = owner)
            event.type == InputType.Down && event.pointerId != pointerId -> ToolResult(next = owner)
            event.pointerId != pointerId -> ToolResult.Nothing
            event.type == InputType.Up -> ToolResult(owner.commandAt(event.point, ctx), null, ImageTool(null))
            else -> ToolResult.Nothing
        }
    }

    companion object {
        /** Longest side of a freshly placed image, in scene units (1 unit = 1/16 inch). */
        const val MAX_PLACED_SIDE_UNITS = 256.0
    }
}
