// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintParts
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.PartAnchors
import dev.stapler.stelekit.sketch.v2.constraints.PartRef
import dev.stapler.stelekit.sketch.v2.constraints.SceneToProblem
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings
import kotlin.math.hypot

/** What a marker looks like: shape carries the meaning, never colour alone (UX-98). */
enum class MarkerShape { Square, Circle, Bar }

/**
 * One valid target of the armed picker: where its marker sits in scene units, how it is named in the hover chip
 * ([chip], "Endpoint of Line 3") and in the "Pick target" list ([listName], "Line 3 end", grouped under [group]), and which
 * other targets share its location ([also], "Line 4 start").
 */
data class TargetCandidate(
    val target: ConstraintTarget,
    val x: Double,
    val y: Double,
    val shape: MarkerShape,
    val chip: String,
    val listName: String,
    val group: String,
    val also: List<String>,
    val picked: Boolean,
) {
    /** The full hover or focus text: the name plus every "also: ..." at the same place. */
    val chipText: String get() = SketchConstraintStrings.Picker.chip(chip, also)

    val key: String get() = "${target.elementId.value}:${target.part.label}"
}

/** An armed picker: which constraint it builds, what is picked so far and which marker has keyboard focus ([NONE] for none). */
data class PickerState(val kind: ConstraintKind, val picks: List<ConstraintTarget> = emptyList(), val focused: Int = NONE) {
    companion object {
        const val NONE = -1
    }
}

/** Which way the keyboard cycle moves. */
enum class FocusStep(val delta: Int) { Next(1), Previous(-1) }

/** What one pick did. [Complete] ends the picker: the host commits [Complete.constraint] (a dimension first opens its label). */
sealed interface PickStep {
    data class Next(val state: PickerState) : PickStep

    /** Nothing changed; [message] is the polite announcement ("Pick a different target"). */
    data class Rejected(val state: PickerState, val message: String) : PickStep

    /** [details] is the toast's Details line, "Added Coincident: Line 1 end, Line 2 start". [needsValue]: a dimension whose label opens next. */
    data class Complete(val constraint: Constraint, val details: String, val needsValue: Boolean) : PickStep
}

/**
 * The pure rules of the armed picker (Task 4.2.6b, UX-98). The host arms it from the Constrain menu, passes the elements to
 * mark (the selected and nearby ones), and feeds each tap, click, Enter or list choice to [pick]. Nothing is armed while the
 * Select tool is just selecting, so clicking still selects whole elements.
 */
object TargetPicker {
    private const val SAME_PLACE = 1e-3

    fun arm(kind: ConstraintKind): PickerState = PickerState(kind)

    /** Every valid next target of the elements in [scope], in the fixed cycle order: element z order, then Start, End, vertices, Centre, edges. */
    fun candidates(doc: SketchDocumentV2, state: PickerState, scope: Collection<ElementId>): List<TargetCandidate> {
        val chosen = state.picks.map { t -> doc.scene[t.elementId]?.let { PartRef(it, t.part) } ?: return emptyList() }
        val inScope = scope.toSet()
        val raw = doc.scene.order.filter { it in inScope }.flatMap { id ->
            val element = doc.scene[id] ?: return@flatMap emptyList()
            ConstraintParts.candidates(state.kind, chosen, element).sortedBy { rank(it) }.mapNotNull { part -> raw(element, part, state) }
        }
        return raw.map { c -> c.copy(also = raw.filter { it !== c && it.shape != MarkerShape.Bar && c.shape != MarkerShape.Bar && near(it, c) }.map { it.listName }) }
    }

    /** The status text of the hint row: "Pick the first point", "Pick an edge" and so on, by what can be picked next. */
    fun statusText(state: PickerState, candidates: List<TargetCandidate>): String {
        val strings = SketchConstraintStrings.Picker
        return when {
            candidates.isEmpty() -> strings.NO_TARGETS
            candidates.all { it.shape == MarkerShape.Bar } -> strings.AN_EDGE
            else -> when (state.picks.size) {
                0 -> strings.FIRST_POINT
                1 -> strings.SECOND_POINT
                else -> strings.THIRD_TARGET
            }
        }
    }

    /** Applies one pick. [newId] mints the id of the constraint a complete pick creates. */
    fun pick(doc: SketchDocumentV2, state: PickerState, target: ConstraintTarget, newId: () -> ConstraintId): PickStep {
        val strings = SketchConstraintStrings.Picker
        val element = doc.scene[target.elementId] ?: return PickStep.Rejected(state, strings.DIFFERENT_TARGET)
        if (target in state.picks) return PickStep.Rejected(state, strings.DIFFERENT_TARGET)
        val chosen = state.picks.map { t -> doc.scene[t.elementId]?.let { PartRef(it, t.part) } ?: return PickStep.Rejected(state, strings.DIFFERENT_TARGET) }
        if (target.part !in ConstraintParts.candidates(state.kind, chosen, element)) return PickStep.Rejected(state, strings.DIFFERENT_TARGET)
        val picks = state.picks + target
        val refs = chosen + PartRef(element, target.part)
        if (!ConstraintParts.accepts(state.kind, refs)) return PickStep.Next(state.copy(picks = picks, focused = PickerState.NONE))
        return complete(doc, state, picks, newId)
    }

    private fun complete(doc: SketchDocumentV2, state: PickerState, picks: List<ConstraintTarget>, newId: () -> ConstraintId): PickStep {
        val strings = SketchConstraintStrings.Picker
        val duplicate = state.kind == ConstraintKind.Coincident && doc.constraints.any { it.kind == ConstraintKind.Coincident && it.targets.toSet() == picks.toSet() }
        if (duplicate) return PickStep.Rejected(state, strings.DIFFERENT_TARGET)
        val id = newId()
        val dimensional = state.kind in Constraint.DIMENSIONAL
        val value = if (dimensional) SceneToProblem.measure(doc.scene, Constraint(id, state.kind, picks, 0.0)).getOrNull() ?: return PickStep.Rejected(state, strings.DIFFERENT_TARGET) else null
        val names = picks.map { t -> doc.scene[t.elementId]?.let { listNameOf(it, t.part) }.orEmpty() }
        val details = strings.added(SketchConstraintStrings.Panel.kindLabel(state.kind), names)
        return PickStep.Complete(Constraint(id, state.kind, picks, value), details, dimensional)
    }

    /** Moves keyboard focus one marker (Tab or an arrow: [FocusStep.Next]; Shift+Tab or an arrow back: [FocusStep.Previous]), wrapping at the ends. */
    fun moveFocus(state: PickerState, count: Int, step: FocusStep): PickerState {
        if (count <= 0) return state.copy(focused = PickerState.NONE)
        val next = when {
            state.focused == PickerState.NONE || state.focused >= count -> if (step == FocusStep.Previous) count - 1 else 0
            else -> (state.focused + step.delta + count) % count
        }
        return state.copy(focused = next)
    }

    /** The Cancel chip for the action chip row: touch has no Esc and the status row may be collapsed (UX-98, D-17). */
    fun cancelChip(onCancel: () -> Unit): ActionChip =
        ActionChip(PickerTags.CANCEL, SketchConstraintStrings.Picker.CANCEL, SketchConstraintStrings.Picker.CANCEL_NAME, onClick = onCancel)

    fun listNameOf(element: SceneElement, part: TargetPart): String {
        val name = ElementNames.nameOf(element)
        val strings = SketchConstraintStrings.Picker
        return when (part) {
            TargetPart.Start, TargetPart.ArcStart -> strings.start(name)
            TargetPart.End, TargetPart.ArcEnd -> strings.end(name)
            is TargetPart.Vertex -> strings.vertex(name, part.index + 1)
            is TargetPart.Edge -> strings.edge(name, (part.index + 1).takeIf { edgeCount(element) > 1 })
            TargetPart.Centre -> strings.centre(name)
            TargetPart.Whole -> if (element is PointElement) name else strings.edge(name, null)
        }
    }

    private fun chipNameOf(element: SceneElement, part: TargetPart): String {
        val name = ElementNames.nameOf(element)
        val strings = SketchConstraintStrings.Picker
        return when (part) {
            TargetPart.Start, TargetPart.End, TargetPart.ArcStart, TargetPart.ArcEnd -> strings.endpointOf(name)
            is TargetPart.Vertex -> strings.vertexOf(name)
            TargetPart.Centre -> strings.centreOf(name)
            is TargetPart.Edge -> strings.edgeOf(name)
            TargetPart.Whole -> if (element is PointElement) name else strings.edgeOf(name)
        }
    }

    private fun shapeOf(element: SceneElement, part: TargetPart): MarkerShape = when {
        part == TargetPart.Centre -> MarkerShape.Circle
        part is TargetPart.Edge || (part == TargetPart.Whole && element !is PointElement) -> MarkerShape.Bar
        else -> MarkerShape.Square
    }

    private fun edgeCount(element: SceneElement): Int = ConstraintParts.partsOf(element).count { it is TargetPart.Edge }

    private fun raw(element: SceneElement, part: TargetPart, state: PickerState): TargetCandidate? {
        val at = PartAnchors.anchorOf(element, part) ?: return null
        val target = ConstraintTarget(element.id, part)
        val shape = shapeOf(element, part)
        return TargetCandidate(target, at.first, at.second, shape, chipNameOf(element, part), listNameOf(element, part), ElementNames.nameOf(element), emptyList(), target in state.picks)
    }

    private fun near(a: TargetCandidate, b: TargetCandidate): Boolean = hypot(a.x - b.x, a.y - b.y) <= SAME_PLACE

    /** Start, End, then vertices, Centre, edges: the order the keyboard cycle walks. */
    private fun rank(part: TargetPart): Int = when (part) {
        TargetPart.Start -> 0
        TargetPart.End -> 1
        TargetPart.ArcStart -> 2
        TargetPart.ArcEnd -> 3
        is TargetPart.Vertex -> VERTEX_BASE + part.index
        TargetPart.Centre -> CENTRE_RANK
        is TargetPart.Edge -> EDGE_BASE + part.index
        TargetPart.Whole -> WHOLE_RANK
    }

    private const val VERTEX_BASE = 10
    private const val CENTRE_RANK = 1000
    private const val EDGE_BASE = 2000
    private const val WHOLE_RANK = 3000
}

object PickerTags {
    const val OVERLAY = "sketch.picker"
    const val STATUS = "sketch.picker.status"
    const val CHIP = "sketch.picker.chip"
    const val CANCEL = "sketch.picker.cancel"
    const val LIST_BUTTON = "sketch.picker.list.button"
    const val LIST = "sketch.picker.list"
    fun marker(key: String) = "sketch.picker.marker.$key"
    fun listRow(key: String) = "sketch.picker.list.row.$key"
}
