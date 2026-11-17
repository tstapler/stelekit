// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.SequenceCommand
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.RemoveConstraint
import dev.stapler.stelekit.sketch.v2.constraints.SetConstraintReference
import dev.stapler.stelekit.sketch.v2.scene.ElementId

/**
 * The armed constraint picker of one editor (Story 4.2.6, Task 6.2.0b): the Constrain menu arms it for a kind, each pick narrows
 * it and a complete pick commits the new constraint through [commit], which the controller runs through the solve runner.
 * Holds the picker state only; the rules live in [TargetPicker].
 */
class ConstraintCreation(
    private val doc: () -> SketchDocumentV2,
    private val selection: () -> List<ElementId>,
    private val newId: () -> ConstraintId,
    private val commit: (Command) -> Unit,
    private val announce: (String) -> Unit,
) {
    /** The armed picker, or null when none is armed. */
    var picker: PickerState? by mutableStateOf(null)
        private set

    /** The constraints the selected list row highlights on the canvas (UX-34). */
    var highlighted: Set<ConstraintId> by mutableStateOf(emptySet())

    /** Takes the draft of a dimension a complete pick built and opens its label editor; true when it did (the host sets it), so nothing is committed. */
    var onDimensionDraft: ((Constraint) -> Boolean)? = null

    /** Arms the picker for [item]; the reference toggle and the kinds with no free target act at once. */
    fun arm(item: ConstrainMenuItem) {
        if (item == ConstrainMenuItem.ReferenceToggle) return toggleReference()
        val kinds = ConstrainFlyoutModel.kindsOf(item)
        val scope = pickScope()
        val state = kinds.map(TargetPicker::arm).firstOrNull { TargetPicker.candidates(doc(), it, scope).isNotEmpty() } ?: TargetPicker.arm(kinds.first())
        picker = state
    }

    fun setState(state: PickerState) {
        picker = state
    }

    fun cancel() {
        picker = null
    }

    /** The markers of the armed picker: the selected elements, or every element when nothing is selected. */
    fun candidates(): List<TargetCandidate> = picker?.let { TargetPicker.candidates(doc(), it, pickScope()) }.orEmpty()

    fun statusText(): String = picker?.let { TargetPicker.statusText(it, candidates()) }.orEmpty()

    fun pick(target: ConstraintTarget) {
        val state = picker ?: return
        when (val step = TargetPicker.pick(doc(), state, target, newId)) {
            is PickStep.Next -> picker = step.state
            is PickStep.Rejected -> {
                picker = step.state
                announce(step.message)
            }
            is PickStep.Complete -> {
                picker = null
                if (step.constraint.isDimensional && onDimensionDraft?.invoke(step.constraint) == true) return
                commit(AddConstraint(step.constraint))
            }
        }
    }

    fun remove(id: ConstraintId) = commit(RemoveConstraint(id))

    fun setReference(id: ConstraintId, reference: Boolean) = commit(SetConstraintReference(id, reference))

    /** Flips the reference flag of every dimension whose targets are all selected. */
    private fun toggleReference() {
        val chosen = selection().toSet()
        val steps = doc().constraints.filter { it.isDimensional && it.elementIds.all { id -> id in chosen } }.map { SetConstraintReference(it.id, !it.reference) }
        if (steps.isNotEmpty()) commit(SequenceCommand(steps))
    }

    private fun pickScope(): Collection<ElementId> = selection().ifEmpty { doc().scene.order }
}
