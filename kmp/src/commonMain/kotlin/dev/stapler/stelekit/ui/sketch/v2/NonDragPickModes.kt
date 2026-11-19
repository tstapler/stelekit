// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.binding.BindingGeometry
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.BindingEnd
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.tools.BindArrowEndCommand
import dev.stapler.stelekit.sketch.v2.tools.Box
import dev.stapler.stelekit.sketch.v2.tools.Selectability
import dev.stapler.stelekit.sketch.v2.tools.ToolCommand
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import kotlin.math.abs
import kotlin.math.roundToInt

/** One shape the "Bind end" list offers: its list label and how far the arrow end is from it (0 inside a filled shape). */
data class BindCandidate(val id: ElementId, val label: String, val distance: Double)

/** Entering Bind end either starts it or says why not. */
sealed interface BindStart {
    data class Started(val mode: NonDragBindMode) : BindStart
    data class Refused(val notice: NonDragNotice) : BindStart
}

/**
 * Bind end without a drag (UX-40, WCAG 2.5.7, ux.md sections 5 and 10): with ONE arrow selected the user picks which end,
 * then a shape from a list ordered by distance, each row labelled by its name and text, and Apply binds it as ONE command.
 * A locked shape may be bound to (owner default, ux.md "Error/edge states"); a shape on a hidden layer is not offered.
 */
class NonDragBindMode private constructor(
    private val scene: SketchSceneV2,
    private val arrow: ArrowElement,
    val end: BindingEnd,
    val candidates: List<BindCandidate>,
    val picked: ElementId?,
) {
    fun withEnd(next: BindingEnd): NonDragBindMode = of(scene, arrow, next, null)

    /** Picks [id] from the list; an id that is not offered changes nothing. */
    fun pick(id: ElementId): NonDragBindMode = if (candidates.any { it.id == id }) NonDragBindMode(scene, arrow, end, candidates, id) else this

    /** Esc: drops a pick and stays, or returns null to leave the mode. */
    fun afterEscape(): NonDragBindMode? = if (picked != null) NonDragBindMode(scene, arrow, end, candidates, null) else null

    val pending: Boolean get() = picked != null

    fun previewText(): String = SketchStrings.NonDrag.bindPreview(endWord(end), candidates.firstOrNull { it.id == picked }?.label)

    /** The ONE command Apply runs; null until a shape is picked. */
    fun apply(): ToolCommand? = picked?.let { BindArrowEndCommand(arrow.id, end, it) }

    companion object {
        fun begin(scene: SketchSceneV2, selection: List<ElementId>, end: BindingEnd = BindingEnd.End): BindStart {
            val present = selection.mapNotNull { scene[it] }
            if (present.isEmpty()) return BindStart.Refused(NonDragNotice.NothingSelected)
            val arrow = present.singleOrNull() as? ArrowElement ?: return BindStart.Refused(NonDragNotice.NotAnArrow)
            if (!Selectability.isSelectable(scene, arrow)) return BindStart.Refused(NonDragNotice.Locked)
            val mode = of(scene, arrow, end, null)
            return if (mode.candidates.isEmpty()) BindStart.Refused(NonDragNotice.NoTargets) else BindStart.Started(mode)
        }

        private fun of(scene: SketchSceneV2, arrow: ArrowElement, end: BindingEnd, picked: ElementId?): NonDragBindMode =
            NonDragBindMode(scene, arrow, end, candidatesFor(scene, arrow, end), picked)

        private fun endWord(end: BindingEnd) = (if (end == BindingEnd.Start) SketchStrings.NonDrag.END_START else SketchStrings.NonDrag.END_END).lowercase()

        private fun candidatesFor(scene: SketchSceneV2, arrow: ArrowElement, end: BindingEnd): List<BindCandidate> {
            val at = (if (end == BindingEnd.Start) arrow.points.firstOrNull() else arrow.points.lastOrNull()) ?: return emptyList()
            val labels = boundLabels(scene)
            return scene.elementsInOrder
                .filter { it.id != arrow.id && BindingGeometry.isBindable(it) && !isBoundLabel(it) && (scene.layer(it.base.layerId) ?: Layer.DefaultLayer).visible }
                .map { it to BindingGeometry.reach(it, at.x.value, at.y.value) }
                .filter { it.second < Double.MAX_VALUE }
                .sortedWith(compareBy({ it.second }, { ElementNames.nameOf(it.first) }))
                .take(scene.limits.maxGoToResults)
                .map { (e, d) -> BindCandidate(e.id, SketchStrings.NonDrag.candidateLabel(ElementNames.nameOf(e), labelText(scene, e, labels)), d) }
        }

        /** A shape's own label is part of the shape, not a second target. */
        private fun isBoundLabel(e: SceneElement) = e is TextElement && e.containerId != null

        private fun boundLabels(scene: SketchSceneV2): Map<ElementId, String> =
            scene.elementsInOrder.mapNotNull { e -> (e as? TextElement)?.containerId?.let { it to e.text } }.toMap()

        private fun labelText(scene: SketchSceneV2, e: SceneElement, labels: Map<ElementId, String>): String {
            val raw = if (e is TextElement && e.containerId == null) e.text else labels[e.id].orEmpty()
            return raw.replace('\n', ' ').trim().take(scene.limits.maxNameChars)
        }
    }
}

/**
 * Marquee without a drag (UX-40, WCAG 2.5.7, ux.md section 10): two taps of "Place corner" define the region corners, the
 * cursor moves with the nudge pad or the arrow keys, and the summary "5 elements in region" offers [Select] and [Cancel].
 * The region selects the same elements a dragged marquee does: selectable elements fully inside it.
 */
class NonDragMarqueeMode private constructor(
    private val scene: SketchSceneV2,
    val step: NudgeStep,
    val cursorX: Double,
    val cursorY: Double,
    private val first: Pair<Double, Double>?,
    private val second: Pair<Double, Double>?,
) {
    /** True once a corner is placed: Esc then starts over and stays in the mode. */
    val pending: Boolean get() = first != null

    val region: Box? get() = if (first == null || second == null) null else Box(
        minOf(first.first, second.first), minOf(first.second, second.second), abs(second.first - first.first), abs(second.second - first.second),
    )

    fun withStep(next: NudgeStep) = NonDragMarqueeMode(scene, next, cursorX, cursorY, first, second)

    fun nudge(dx: Int, dy: Int): NonDragMarqueeMode = move(dx * stepUnits(Axis.X), dy * stepUnits(Axis.Y))

    /** The Shift arrow: always [NonDragTransformMode.FAR_UNITS] units. */
    fun nudgeFar(dx: Int, dy: Int): NonDragMarqueeMode = move(dx * NonDragTransformMode.FAR_UNITS, dy * NonDragTransformMode.FAR_UNITS)

    /** Places the first corner, then the second; a third tap starts a new region at the cursor. */
    fun placeCorner(): NonDragMarqueeMode {
        val here = cursorX to cursorY
        return when {
            first == null || second != null -> NonDragMarqueeMode(scene, step, cursorX, cursorY, here, null)
            else -> NonDragMarqueeMode(scene, step, cursorX, cursorY, first, here)
        }
    }

    /** Esc: drops the placed corners and stays, or returns null to leave the mode. */
    fun afterEscape(): NonDragMarqueeMode? = if (pending) NonDragMarqueeMode(scene, step, cursorX, cursorY, null, null) else null

    /** The selectable elements fully inside the region, in paint order; empty until both corners are placed. */
    fun contained(): List<ElementId> {
        val box = region ?: return emptyList()
        return scene.elementsInOrder
            .filter { Selectability.isSelectable(scene, it) }
            .filter { e -> ElementBounds.geometry(e).let { b -> b.minX >= box.x && b.maxX <= box.x + box.width && b.minY >= box.y && b.maxY <= box.y + box.height } }
            .map { it.id }
    }

    /** "5 elements in region" once the region exists; before that, where the cursor is or the first corner went. */
    fun summaryText(): String = when {
        region != null -> SketchStrings.NonDrag.regionSummary(contained().size)
        first != null -> SketchStrings.NonDrag.firstCorner(first.first.roundToInt(), first.second.roundToInt())
        else -> SketchStrings.NonDrag.cursorAt(cursorX.roundToInt(), cursorY.roundToInt())
    }

    private fun move(dx: Double, dy: Double) = NonDragMarqueeMode(scene, step, cursorX + dx, cursorY + dy, first, second)

    private enum class Axis { X, Y }

    private fun stepUnits(axis: Axis): Double = when (step) {
        NudgeStep.One -> ONE_UNIT
        NudgeStep.Ten -> NonDragTransformMode.FAR_UNITS
        NudgeStep.Percent -> contentExtent(axis) / PERCENT
    }

    /** The extent of the drawing on [axis], so "1%" scales with it; one hundred units for an empty scene. */
    private fun contentExtent(axis: Axis): Double {
        val boxes = scene.elementsInOrder.map { ElementBounds.geometry(it) }
        if (boxes.isEmpty()) return PERCENT
        val span = if (axis == Axis.X) boxes.maxOf { it.maxX } - boxes.minOf { it.minX } else boxes.maxOf { it.maxY } - boxes.minOf { it.minY }
        return if (span > 0.0) span else PERCENT
    }

    companion object {
        private const val ONE_UNIT = 1.0
        private const val PERCENT = 100.0

        /** Starts with the cursor at ([x], [y]), the crosshair position of the host. */
        fun begin(scene: SketchSceneV2, x: Double, y: Double, step: NudgeStep = NudgeStep.Ten) =
            NonDragMarqueeMode(scene, step, x, y, null, null)
    }
}
