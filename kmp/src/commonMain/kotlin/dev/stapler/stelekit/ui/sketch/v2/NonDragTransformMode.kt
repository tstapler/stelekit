// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.TextSizeLimits
import dev.stapler.stelekit.sketch.v2.tools.Box
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.sketch.v2.tools.ElementTransform
import dev.stapler.stelekit.sketch.v2.tools.FrameMembership
import dev.stapler.stelekit.sketch.v2.tools.Handle
import dev.stapler.stelekit.sketch.v2.tools.MoveSelectionCommand
import dev.stapler.stelekit.sketch.v2.tools.Selectability
import dev.stapler.stelekit.sketch.v2.tools.SelectTool
import dev.stapler.stelekit.sketch.v2.tools.TextResize
import dev.stapler.stelekit.sketch.v2.tools.ToolCommand
import dev.stapler.stelekit.sketch.v2.tools.ToolContext
import dev.stapler.stelekit.sketch.v2.tools.ToolPreview
import dev.stapler.stelekit.sketch.v2.tools.TransformMath
import dev.stapler.stelekit.sketch.v2.tools.TransformSelectionCommand
import dev.stapler.stelekit.sketch.v2.tools.ownAngle
import dev.stapler.stelekit.sketch.v2.tools.ownBox
import dev.stapler.stelekit.sketch.v2.tools.previewOf
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToLong

/** What the non-drag layer can do to a selection (Story 3.6.3, Task 3.6.3a); bind and marquee are the modes of `NonDragPickModes.kt` (Task 3.6.3b). */
enum class NonDragAction(val label: String) {
    MoveElement(SketchStrings.NonDrag.MOVE_ELEMENT),
    Resize(SketchStrings.NonDrag.RESIZE),
    Rotate(SketchStrings.NonDrag.ROTATE),
}

/** The nudge step of the pad; [label]s are the ones of the Crosshair pad (ux.md section 10). Steps are scene units. */
enum class NudgeStep(val label: String) {
    One(SketchStrings.NonDrag.STEP_ONE),
    Ten(SketchStrings.NonDrag.STEP_TEN),
    Percent(SketchStrings.NonDrag.STEP_PERCENT),
}

/** Why a mode did not start or a change was limited; the host owns the wording (UX-12). */
enum class NonDragNotice(val text: String) {
    /** Resize hit the smallest side; the result is clamped to it. */
    MinimumSize(SketchStrings.NonDrag.MINIMUM_SIZE),

    /** A text resize hit the largest font size; the result is clamped to it. */
    MaximumSize(SketchStrings.NonDrag.MAXIMUM_SIZE),

    /** Nothing in the selection can be edited from the canvas (all locked, or on a locked or hidden layer). */
    Locked(SketchStrings.NonDrag.LOCKED),

    NothingSelected(SketchStrings.NonDrag.NOTHING_SELECTED),

    /** Rotate needs rectangles, diamonds, ellipses, text, images or frames. */
    NotRotatable(SketchStrings.NonDrag.NOT_ROTATABLE),

    /** Bind end needs exactly one arrow. */
    NotAnArrow(SketchStrings.NonDrag.NOT_AN_ARROW),

    /** No shape can take the arrow end (the scene has none, or all are on hidden layers). */
    NoTargets(SketchStrings.NonDrag.NO_TARGETS),
}

/** The outcome of Apply: the command to run (null when nothing changed) and what to tell the user. */
data class NonDragApplied(val command: ToolCommand?, val notice: NonDragNotice?, val skippedLocked: Int)

/** Entering the mode either starts it or says why not. */
sealed interface NonDragStart {
    data class Started(val mode: NonDragTransformMode) : NonDragStart
    data class Refused(val notice: NonDragNotice) : NonDragStart
}

/**
 * Move element, Resize and Rotate without a drag (UX-40, WCAG 2.5.7, D-39). The user selects, chooses an action, nudges
 * with the pad or the arrow keys (Shift = 10 units) and applies; nothing reaches the document until [apply], so [cancel]
 * and Esc discard it, and Apply is ONE command, so one undo step. Pure and immutable: each nudge returns a new mode.
 * Locked elements and elements on locked or hidden layers are never edited; a mixed selection edits the rest and reports
 * how many were skipped.
 */
class NonDragTransformMode private constructor(
    val action: NonDragAction,
    val step: NudgeStep,
    private val scene: SketchSceneV2,
    private val items: List<SceneElement>,
    private val skipped: Int,
    private val frame: Box,
    private val frameAngle: Double,
    private val offsetX: Double,
    private val offsetY: Double,
) {
    /** True while a nudge is pending: Esc then discards it and stays in the mode (Esc row 7). */
    val pending: Boolean get() = offsetX != 0.0 || offsetY != 0.0

    fun withStep(next: NudgeStep) = copyWith(step = next)

    /** One nudge of [dx] or [dy] steps (-1, 0 or 1) of the chosen step. */
    fun nudge(dx: Int, dy: Int): NonDragTransformMode =
        copyWith(offsetX = offsetX + dx * stepUnits(frame.width), offsetY = offsetY + dy * stepUnits(frame.height))

    /** The Shift arrow: always [FAR_UNITS] units, whatever the step setting. */
    fun nudgeFar(dx: Int, dy: Int): NonDragTransformMode =
        copyWith(offsetX = offsetX + dx * FAR_UNITS, offsetY = offsetY + dy * FAR_UNITS)

    /** Esc: discards a pending nudge and stays, or returns null to leave the mode. */
    fun afterEscape(): NonDragTransformMode? = if (pending) copyWith(offsetX = 0.0, offsetY = 0.0) else null

    private fun stepUnits(extent: Double): Double = when (step) {
        NudgeStep.One -> ONE_UNIT
        NudgeStep.Ten -> FAR_UNITS
        NudgeStep.Percent -> extent / PERCENT
    }

    /** "x +30, y 0", "width 120 → 150, height 80 → 80" or "angle 0° → 15°": what Apply would do. */
    fun previewText(): String = when (action) {
        NonDragAction.MoveElement -> SketchStrings.NonDrag.movePreview(Readout.signed(offsetX), Readout.signed(offsetY))
        NonDragAction.Resize -> (textResize()?.transform?.let { Box(it.x, it.y, it.width, it.height) } ?: resized()).let {
            SketchStrings.NonDrag.resizePreview(Readout.number(frame.width), Readout.number(it.width), Readout.number(frame.height), Readout.number(it.height))
        }
        NonDragAction.Rotate -> SketchStrings.NonDrag.rotatePreview(
            Readout.number(Readout.degrees(frameAngle)),
            Readout.number(Readout.degrees(TransformMath.normalise(frameAngle + deltaAngle()))),
        )
    }

    /** The "Minimum size reached" notice when the pending resize is clamped (for a text, the "Maximum size" one when its font limit stops it). */
    fun pendingNotice(): NonDragNotice? {
        if (action != NonDragAction.Resize || !pending) return null
        textResize()?.let { return it.hit?.let { hit -> if (hit == TextSizeLimits.Bound.Min) NonDragNotice.MinimumSize else NonDragNotice.MaximumSize } }
        return if (frame.width + offsetX < TransformMath.MIN_SIDE || frame.height + offsetY < TransformMath.MIN_SIDE) NonDragNotice.MinimumSize else null
    }

    /** The edited elements as they would look, for the live preview. */
    fun preview(ids: ElementIdSource): ToolPreview? =
        command()?.let { previewOf(it, ToolContext(scene, PREVIEW_ZOOM, ElementStyle(), ids), items.map { e -> e.id }) }

    /** Runs the pending change as ONE command; null when nothing is pending. */
    fun apply(): NonDragApplied = NonDragApplied(command(), pendingNotice(), skipped)

    private fun command(): ToolCommand? = if (!pending) null else when (action) {
        NonDragAction.MoveElement -> move()
        NonDragAction.Resize -> resizeSteps()?.let(::TransformSelectionCommand)
        NonDragAction.Rotate -> TransformSelectionCommand(rotateSteps())
    }

    private fun move(): ToolCommand {
        val ids = items.map { it.id }
        return MoveSelectionCommand(ids, offsetX, offsetY, FrameMembership.changesAfterMove(scene, ids.toSet(), offsetX, offsetY))
    }

    /** The joint box after pulling the east and south edges by the offset (the same maths as the east, south-east and south handles). */
    private fun resized(): Box = TransformMath.resize(frame, frameAngle, Handle.SE, offsetX, offsetY, keepAspect = false, fromCentre = false)

    /** A single text scales its font ([TextResize.nudge]) instead of stretching its box; null otherwise. */
    private fun textResize(): TextResize.Nudged? = (items.singleOrNull() as? TextElement)?.let {
        val box = ownBox(it)
        TextResize.nudge(it, box, TransformMath.resize(box, it.angle, Handle.SE, offsetX, offsetY, keepAspect = false, fromCentre = false))
    }

    /** Null when a single text's font cannot move (at its limit), so Apply commits nothing. */
    private fun resizeSteps(): List<ElementTransform>? {
        textResize()?.let { n -> return listOf(n.transform).takeIf { n.transform.fontSize != (items.single() as TextElement).fontSize } }
        val next = resized()
        if (items.size == 1) return listOf(fit(items.single(), next, null))
        return items.map { e ->
            val mapped = TransformMath.mapBox(ownBox(e), frame, next)
            when (e) {
                is PointElement, is ArcElement -> shift(e, mapped)
                is TextElement -> TextResize.groupScale(e, ownBox(e), mapped)
                else -> fit(e, mapped, null)
            }
        }
    }

    private fun deltaAngle(): Double = (offsetX + offsetY) * (if (step == NudgeStep.Percent) PERCENT_TURN_DEGREES else 1.0) * PI / HALF_TURN_DEGREES

    private fun rotateSteps(): List<ElementTransform> {
        val delta = deltaAngle()
        if (items.size == 1) {
            val e = items.single()
            return listOf(fit(e, ownBox(e), TransformMath.normalise(ownAngle(e) + delta)))
        }
        return items.map { e ->
            val b = ownBox(e)
            val (cx, cy) = TransformMath.rotatePoint(frame.cx, frame.cy, b.cx, b.cy, delta)
            fit(e, Box(cx - b.width / 2.0, cy - b.height / 2.0, b.width, b.height), TransformMath.normalise(ownAngle(e) + delta))
        }
    }

    private fun fit(e: SceneElement, b: Box, angle: Double?): ElementTransform = ElementTransform.Fit(e.id, b.x, b.y, b.width, b.height, angle)

    private fun shift(e: SceneElement, mapped: Box): ElementTransform {
        val old = ownBox(e)
        return ElementTransform.Shift(e.id, mapped.cx - old.cx, mapped.cy - old.cy)
    }

    private fun copyWith(step: NudgeStep = this.step, offsetX: Double = this.offsetX, offsetY: Double = this.offsetY) =
        NonDragTransformMode(action, step, scene, items, skipped, frame, frameAngle, offsetX, offsetY)

    companion object {
        /** The step of the "10 px" setting and of Shift. */
        const val FAR_UNITS = 10.0
        private const val ONE_UNIT = 1.0
        private const val PERCENT = 100.0
        private const val PERCENT_TURN_DEGREES = 3.6
        private const val HALF_TURN_DEGREES = 180.0
        private const val PREVIEW_ZOOM = 1.0

        /** Enters [action] on [selection]; refuses when nothing can be edited or the action does not apply. */
        fun begin(action: NonDragAction, scene: SketchSceneV2, selection: List<ElementId>, step: NudgeStep = NudgeStep.Ten): NonDragStart {
            val present = selection.mapNotNull { scene[it] }
            if (present.isEmpty()) return NonDragStart.Refused(NonDragNotice.NothingSelected)
            val editable = present.filter { Selectability.isSelectable(scene, it) }
            if (editable.isEmpty()) return NonDragStart.Refused(NonDragNotice.Locked)
            val frame = SelectTool(editable.map { it.id }).selectionFrame(ToolContext(scene, PREVIEW_ZOOM, ElementStyle(), ElementIdSource { error("no ids are minted by a transform") }))
                ?: return NonDragStart.Refused(NonDragNotice.NothingSelected)
            if (action == NonDragAction.Rotate && !frame.rotatable) return NonDragStart.Refused(NonDragNotice.NotRotatable)
            val mode = NonDragTransformMode(action, step, scene, editable, present.size - editable.size, frame.box, frame.angle, 0.0, 0.0)
            return NonDragStart.Started(mode)
        }
    }
}

/** How the preview line writes numbers: whole when whole, else one decimal, with a sign for a displacement. */
internal object Readout {
    private const val TENTHS = 10.0
    private const val ROUNDING = 1e-9
    private const val HALF_TURN_DEGREES = 180.0

    fun signed(v: Double): String = (if (v > 0.0) "+" else "") + number(v)

    fun number(v: Double): String =
        if (abs(v - v.roundToLong()) < ROUNDING) v.roundToLong().toString() else (((v * TENTHS).roundToLong()) / TENTHS).toString()

    fun degrees(radians: Double): Double = radians * HALF_TURN_DEGREES / PI
}
