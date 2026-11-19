// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The modifiers of a drag-drawn shape (Story 3.2.1). [square] and [fromCentre] are the toggle chips of the action chip
 * row, so a touch user has the same control as Shift (square) and Alt (from centre). [altFromCentre] is false on Linux,
 * where Alt+drag moves the window and no function may depend on Alt alone (UX-109).
 */
data class ShapeOptions(
    val square: Boolean = false,
    val fromCentre: Boolean = false,
    val altFromCentre: Boolean = true,
)

/** The unrotated box a shape drag describes, in scene units. */
data class DragBox(val x: Double, val y: Double, val width: Double, val height: Double) {
    val cx: Double get() = x + width / HALF
    val cy: Double get() = y + height / HALF

    companion object {
        private const val HALF = 2.0

        /**
         * The box from the press at [start] to the pointer at [end]. [square] makes both sides the longer one, keeping the
         * drag direction; [fromCentre] treats [start] as the centre, so the box extends by the drag on both sides.
         */
        fun of(start: ScenePoint, end: ScenePoint, effective: ShapeOptions): DragBox {
            var dx = end.x.value - start.x.value
            var dy = end.y.value - start.y.value
            if (effective.square) {
                val side = max(abs(dx), abs(dy))
                dx = if (dx < 0.0) -side else side
                dy = if (dy < 0.0) -side else side
            }
            val w = abs(dx)
            val h = abs(dy)
            return if (effective.fromCentre) {
                DragBox(start.x.value - w, start.y.value - h, w * HALF, h * HALF)
            } else {
                DragBox(min(start.x.value, start.x.value + dx), min(start.y.value, start.y.value + dy), w, h)
            }
        }
    }
}

/**
 * Rectangle, diamond and ellipse share one gesture: press, drag, release (Story 3.2.1, Task 3.2.1a). Shift or the Square
 * chip constrains to a square or circle, Alt (not on Linux) or the From centre chip draws from the centre. A drag under
 * [MIN_SIDE] on either side is a stray click and commits nothing. Cancel rules are [DragGestureTool]'s.
 */
abstract class BoxShapeTool(private val options: ShapeOptions) : DragGestureTool() {
    protected abstract fun shape(box: DragBox, base: ElementBase): SceneElement

    override fun idle(): Tool = this

    private fun boxFor(start: ScenePoint, end: ScenePoint, event: InputEvent): DragBox = DragBox.of(
        start, end,
        options.copy(
            square = options.square || event.modifiers.shift,
            fromCentre = options.fromCentre || (options.altFromCentre && event.modifiers.alt),
        ),
    )

    override fun previewFor(start: ScenePoint, current: ScenePoint, event: InputEvent, ctx: ToolContext): ToolPreview =
        ToolPreview(listOf(shape(boxFor(start, current, event), ElementBase(PREVIEW_ID, style = ctx.style))))

    override fun commandFor(start: ScenePoint, end: ScenePoint, event: InputEvent, ctx: ToolContext): ToolCommand? {
        val box = boxFor(start, end, event)
        if (box.width < MIN_SIDE || box.height < MIN_SIDE) return null
        return AddElementCommand(shape(box, ElementBase(ctx.ids.next(), style = ctx.style)))
    }

    companion object {
        /** A shape narrower than this many scene units on a side is a stray click. */
        const val MIN_SIDE = 2.0
    }
}

class RectangleTool(options: ShapeOptions = ShapeOptions()) : BoxShapeTool(options) {
    override val id: ToolId = ToolId.Rectangle

    override fun shape(box: DragBox, base: ElementBase): SceneElement =
        RectElement(base, unitOrZero(box.x), unitOrZero(box.y), unitOrZero(box.width), unitOrZero(box.height))
}

class DiamondTool(options: ShapeOptions = ShapeOptions()) : BoxShapeTool(options) {
    override val id: ToolId = ToolId.Diamond

    override fun shape(box: DragBox, base: ElementBase): SceneElement =
        DiamondElement(base, unitOrZero(box.x), unitOrZero(box.y), unitOrZero(box.width), unitOrZero(box.height))
}

/** The box becomes the ellipse's bounding box: centre at its middle, radii half its sides; a circle when the box is square. */
class EllipseTool(options: ShapeOptions = ShapeOptions()) : BoxShapeTool(options) {
    override val id: ToolId = ToolId.Ellipse

    private val half = 2.0

    override fun shape(box: DragBox, base: ElementBase): SceneElement = EllipseElement(
        base, unitOrZero(box.cx), unitOrZero(box.cy),
        unitOrZero(max(box.width / half, EMPTY_RADIUS)), unitOrZero(max(box.height / half, EMPTY_RADIUS)),
    )

    private companion object {
        /** A press with no drag previews as a dot: the radius an [EllipseElement] needs to exist (it must be positive). */
        const val EMPTY_RADIUS = 0.01
    }
}
