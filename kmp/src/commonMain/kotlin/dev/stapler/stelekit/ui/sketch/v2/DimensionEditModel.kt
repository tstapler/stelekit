// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.DimensionInput
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.DimensionAdd
import dev.stapler.stelekit.sketch.v2.constraints.DimensionAddPlan
import dev.stapler.stelekit.sketch.v2.constraints.DimensionSuggestion
import dev.stapler.stelekit.sketch.v2.constraints.SetConstraintReference
import dev.stapler.stelekit.sketch.v2.constraints.SetDimensionValue
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveOutcome
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRejection
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings
import kotlin.math.PI

/** Turns what the user typed into a dimension's value in scene units (a length in sixteenths of an inch, an angle in radians). */
object DimensionValueParser {
    private const val HALF_TURN_DEGREES = 180.0

    fun fieldLabel(kind: ConstraintKind): String = when (kind) {
        ConstraintKind.Radius -> SketchConstraintStrings.Dimension.FIELD_RADIUS
        ConstraintKind.Diameter -> SketchConstraintStrings.Dimension.FIELD_DIAMETER
        ConstraintKind.Angle -> SketchConstraintStrings.Dimension.FIELD_ANGLE
        else -> SketchConstraintStrings.Dimension.FIELD_LENGTH
    }

    /** The value, or the inline message to show with the old value kept ("Not a length. Try 12 3/16\""). */
    fun parse(kind: ConstraintKind, text: String, technical: Boolean): Either<String, Double> = when {
        kind == ConstraintKind.Angle -> angle(text)
        technical -> DimensionInput.parse(text).fold({ SketchConstraintStrings.Dimension.notALength().left() }, { it.sixteenths.toDouble().right() })
        else -> text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }?.right() ?: SketchConstraintStrings.Dimension.notALength().left()
    }

    private fun angle(text: String): Either<String, Double> {
        val degrees = text.trim().removeSuffix("°").trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        return degrees?.let { (it * PI / HALF_TURN_DEGREES).right() } ?: SketchConstraintStrings.Dimension.NOT_AN_ANGLE.left()
    }
}

/** A dimension the editor works on: an existing one ([isNew] false) or the draft of one a chip or the picker is about to add. */
data class DimensionSubject(val constraint: Constraint, val isNew: Boolean) {
    val fieldLabel: String get() = DimensionValueParser.fieldLabel(constraint.kind)
}

/** What a button of the failure card does. */
enum class DimensionFailureKind { KeepEditing, Revert, MakeReference }

data class DimensionFailureAction(val kind: DimensionFailureKind, val label: String)

sealed interface DimensionEditState {
    data object Closed : DimensionEditState

    /** The field is open in place with [text] selected; [error] is the inline message of a value that did not parse. */
    data class Editing(val subject: DimensionSubject, val text: String, val error: String? = null) : DimensionEditState

    /** A reference dimension is driven by other constraints: its label is read-only and offers Make driving. */
    data class ReadOnly(val id: ConstraintId, val message: String, val actionLabel: String) : DimensionEditState

    /** The new value could not be solved; the old value and geometry stayed. [typed] is kept for Keep editing. */
    data class Failed(
        val subject: DimensionSubject,
        val typed: String,
        val message: String,
        val actions: List<DimensionFailureAction>,
    ) : DimensionEditState
}

/**
 * The dimension label editor's state machine (Tasks 4.2.7b and 4.2.7d, UX-99, UX-115). It opens for an existing dimension
 * (tap or click its label, Enter or F2 on its glyph or row), or for a draft a suggestion chip or the target picker hands it. A
 * typed value commits through the same [SolveRunner] path as every edit, so the new value and the solver-moved geometry are ONE
 * undo step, and a value the solver refuses leaves the document untouched. Esc cancels and commits nothing.
 */
class DimensionEditor(private val technicalNow: () -> Boolean) {
    constructor(technical: Boolean) : this({ technical })

    private val technical: Boolean get() = technicalNow()

    var state: DimensionEditState by mutableStateOf(DimensionEditState.Closed)
        private set

    /** The conflict card of a NEW dimension the solver refused (Replace existing and the other actions of UX-32); the host shows it. */
    var card: ConflictCard? by mutableStateOf(null)
        private set

    /** Opens the label of [id]; false when it is not a dimension of this sheet. */
    fun openExisting(doc: SketchDocumentV2, id: ConstraintId): Boolean {
        val c = doc.constraints.firstOrNull { it.id == id && it.isDimensional } ?: return false
        card = null
        state = if (c.reference) {
            DimensionEditState.ReadOnly(id, SketchConstraintStrings.Dimension.REFERENCE_READ_ONLY, SketchConstraintStrings.Dimension.MAKE_DRIVING)
        } else {
            DimensionEditState.Editing(DimensionSubject(c, isNew = false), ConstraintValueText.of(c, technical).orEmpty())
        }
        return true
    }

    /** Opens the label of a new dimension [draft] (already holding the current measure) with [prefill] selected; a duplicate opens the existing one instead. */
    fun openNew(doc: SketchDocumentV2, draft: Constraint, prefill: String) {
        val plan = DimensionAdd.plan(doc, draft)
        if (plan is DimensionAddPlan.EditExisting && openExisting(doc, plan.id)) return
        card = null
        state = DimensionEditState.Editing(DimensionSubject(draft, isNew = true), prefill)
    }

    /** Accepting a suggestion chip opens the label; false for a suggestion the constraint model cannot hold yet. */
    fun openSuggestion(doc: SketchDocumentV2, suggestion: DimensionSuggestion): Boolean {
        if (!suggestion.addable) return false
        openNew(doc, suggestion.constraint, suggestion.prefill)
        return true
    }

    fun typed(text: String) {
        val editing = state as? DimensionEditState.Editing ?: return
        state = editing.copy(text = text, error = null)
    }

    /** Esc, or Cancel: commits nothing and closes whichever of the field, the read-only label or the failure card is open. */
    fun cancel() {
        state = DimensionEditState.Closed
    }

    fun dismissCard() {
        card = null
    }

    /**
     * Enter: parses the text and commits it through [runner]. An unparsable value keeps the field open with the inline message
     * and the old value; null is returned when nothing was committed (nothing open, a parse error, or a cancelled solve).
     */
    suspend fun submit(base: SketchDocumentV2, runner: SolveRunner): SolveOutcome? {
        val editing = state as? DimensionEditState.Editing ?: return null
        val subject = editing.subject
        val value = DimensionValueParser.parse(subject.constraint.kind, editing.text, technical).fold(
            { message -> state = editing.copy(error = message); return null },
            { it },
        )
        val command = if (subject.isNew) AddConstraint(subject.constraint.copy(value = value)) else SetDimensionValue(subject.constraint.id, value)
        val outcome = runner.commitEdit(base, command)
        when (outcome) {
            is SolveOutcome.Done, is SolveOutcome.Unchanged, is SolveOutcome.Posed -> state = DimensionEditState.Closed
            is SolveOutcome.Cancelled -> Unit
            is SolveOutcome.Rejected -> rejected(base, editing, subject.constraint.copy(value = value), outcome.reason)
        }
        return outcome.takeUnless { it is SolveOutcome.Cancelled }
    }

    /** A button of the failure card: Keep editing reopens the field with the typed text, Revert closes, Make reference commits it as a reference. */
    suspend fun choose(kind: DimensionFailureKind, base: SketchDocumentV2, runner: SolveRunner): SolveOutcome? {
        val failed = state as? DimensionEditState.Failed ?: return null
        return when (kind) {
            DimensionFailureKind.KeepEditing -> null.also { state = DimensionEditState.Editing(failed.subject, failed.typed) }
            DimensionFailureKind.Revert -> null.also { state = DimensionEditState.Closed }
            DimensionFailureKind.MakeReference -> runner.commitEdit(base, SetConstraintReference(failed.subject.constraint.id, true))
                .also { if (it is SolveOutcome.Done || it is SolveOutcome.Unchanged) state = DimensionEditState.Closed }
        }
    }

    /** Make driving on a read-only reference label: commits and, when it worked, opens the now-driving label for editing. */
    suspend fun makeDriving(base: SketchDocumentV2, runner: SolveRunner): SolveOutcome? {
        val readOnly = state as? DimensionEditState.ReadOnly ?: return null
        val outcome = runner.commitEdit(base, SetConstraintReference(readOnly.id, false))
        if (outcome is SolveOutcome.Done) {
            state = DimensionEditState.Closed
            openExisting(outcome.step.after, readOnly.id)
        }
        return outcome
    }

    private fun rejected(base: SketchDocumentV2, editing: DimensionEditState.Editing, attempted: Constraint, reason: SolveRejection) {
        val subject = editing.subject
        val unsatisfiable = reason as? SolveRejection.Unsatisfiable
        if (subject.isNew && unsatisfiable != null) {
            card = ConflictCards.forRejected(base, attempted, unsatisfiable)
            state = DimensionEditState.Closed
            return
        }
        val duplicate = (reason as? SolveRejection.EditFailed)?.error as? CommandError.DuplicateDimension
        if (duplicate != null && openExisting(base, ConstraintId(duplicate.existingId))) return
        state = DimensionEditState.Failed(subject, editing.text, failureMessage(base, subject, attempted, reason), failureActions(subject))
    }

    private fun failureMessage(base: SketchDocumentV2, subject: DimensionSubject, attempted: Constraint, reason: SolveRejection): String {
        val strings = SketchConstraintStrings.Dimension
        if (subject.isNew) return strings.CANNOT_ADD
        val old = base.constraints.firstOrNull { it.id == subject.constraint.id }?.let { ConstraintValueText.of(it, technical) }.orEmpty()
        val new = ConstraintValueText.of(attempted, technical).orEmpty()
        val ids = conflictIdsOf(reason)
        val partner = base.constraints.firstOrNull { it.id.value in ids && it.id != subject.constraint.id } ?: return strings.cannotSolve(old, new)
        val shared = partner.elementIds.firstOrNull { it in subject.constraint.elementIds } ?: partner.elementIds.firstOrNull()
        val element = shared?.let { base.scene[it] }?.let { ElementNames.nameOf(it) }.orEmpty()
        return strings.cannotSolveConflict(old, new, SketchConstraintStrings.Panel.kindLabel(partner.kind), element)
    }

    private fun failureActions(subject: DimensionSubject): List<DimensionFailureAction> = buildList {
        val strings = SketchConstraintStrings.Dimension
        add(DimensionFailureAction(DimensionFailureKind.KeepEditing, strings.KEEP_EDITING))
        add(DimensionFailureAction(DimensionFailureKind.Revert, strings.REVERT))
        if (!subject.isNew) add(DimensionFailureAction(DimensionFailureKind.MakeReference, strings.MAKE_REFERENCE))
    }

    private fun conflictIdsOf(reason: SolveRejection): List<String> = when (reason) {
        is SolveRejection.Unsatisfiable -> reason.ids.map { it.value }
        is SolveRejection.EditFailed -> (reason.error as? CommandError.Unsolvable)?.conflictIds.orEmpty()
        is SolveRejection.Crashed -> emptyList()
    }
}
