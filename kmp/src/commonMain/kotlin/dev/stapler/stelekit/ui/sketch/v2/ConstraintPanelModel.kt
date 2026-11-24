// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.MetricScene
import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.SequenceCommand
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintEligibility
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintParts
import dev.stapler.stelekit.sketch.v2.constraints.DimensionAdd
import dev.stapler.stelekit.sketch.v2.constraints.DofWording
import dev.stapler.stelekit.sketch.v2.constraints.Eligibility
import dev.stapler.stelekit.sketch.v2.constraints.PartRef
import dev.stapler.stelekit.sketch.v2.constraints.RemoveConstraint
import dev.stapler.stelekit.sketch.v2.constraints.SetConstraintReference
import dev.stapler.stelekit.sketch.v2.constraints.SetDimensionValue
import dev.stapler.stelekit.sketch.v2.constraints.SketchReasonText
import dev.stapler.stelekit.sketch.v2.constraints.SolverTierGate
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.DiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveOutcome
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRejection
import dev.stapler.stelekit.sketch.v2.runtime.constraints.UnsatisfiedKind
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter
import dev.stapler.stelekit.units.MetricLength
import kotlin.math.PI
import kotlin.math.roundToLong

/** What the last solve said about the sheet's constraints: the degrees of freedom, the redundant ones and the ones that disagree. */
data class ConstraintAnalysis(
    val dof: Int? = null,
    val redundant: Set<ConstraintId> = emptySet(),
    val conflicts: Set<ConstraintId> = emptySet(),
    val conflictCount: Int = conflicts.size,
) {
    companion object {
        val Unknown = ConstraintAnalysis()

        /** Reads the outcome of `SolveRunner.analyze` or of an edit: a solved pose carries the count, a refusal the clashing constraints. */
        fun from(outcome: SolveOutcome): ConstraintAnalysis = when (outcome) {
            is SolveOutcome.Posed -> outcome.summary?.let { ConstraintAnalysis(it.dof, it.redundant.toSet()) } ?: Unknown
            is SolveOutcome.Done -> outcome.summary?.let { ConstraintAnalysis(it.dof, it.redundant.toSet()) } ?: Unknown
            is SolveOutcome.Rejected -> (outcome.reason as? SolveRejection.Unsatisfiable)?.let {
                ConstraintAnalysis(conflicts = it.ids.toSet(), conflictCount = it.ids.size.coerceAtLeast(1))
            } ?: Unknown
            is SolveOutcome.Unchanged, is SolveOutcome.Cancelled -> Unknown
        }
    }
}

enum class StatusKind { Free, Fully, Over }

/** The status line of UX-31: always one of three states, only on a sheet with constraints or a technical sheet. */
data class ConstraintStatus(val kind: StatusKind, val text: String, val dof: Int?)

enum class ConstraintRowState(val stateText: String, val glyph: String) {
    Ok(SketchConstraintStrings.Panel.STATE_OK, SketchConstraintStrings.Panel.GLYPH_OK),
    Redundant(SketchConstraintStrings.Panel.STATE_REDUNDANT, SketchConstraintStrings.Panel.GLYPH_REDUNDANT),
    Conflict(SketchConstraintStrings.Panel.STATE_CONFLICT, SketchConstraintStrings.Panel.GLYPH_CONFLICT),
}

/** One row of the Constraints list: the text, its state as a glyph AND words (never colour alone, UX-33) and the geometry it highlights (UX-34). */
data class ConstraintRow(
    val id: ConstraintId,
    val text: String,
    val state: ConstraintRowState,
    val reference: Boolean,
    val dimensional: Boolean,
    val elementNames: List<String>,
    val elementIds: Set<ElementId>,
)

/** Pure model of the Constraints tab (Task 4.2.3a). */
object ConstraintPanelModel {
    /** The status line, or null when none belongs on this sheet (a plain diagram sheet with no constraints) or the count is not known yet. */
    fun status(doc: SketchDocumentV2, analysis: ConstraintAnalysis, technical: Boolean): ConstraintStatus? {
        if (doc.constraints.isEmpty() && !technical) return null
        if (analysis.conflictCount > 0) {
            return ConstraintStatus(StatusKind.Over, SketchConstraintStrings.Panel.overConstrained(analysis.conflictCount), null)
        }
        val dof = analysis.dof ?: return null
        val kind = if (dof <= 0) StatusKind.Fully else StatusKind.Free
        return ConstraintStatus(kind, DofWording.statusWithDetail(dof), dof)
    }

    fun rows(doc: SketchDocumentV2, analysis: ConstraintAnalysis, technical: Boolean, format: LengthFormat = LengthFormat.INCHES_MIXED): List<ConstraintRow> = doc.constraints.map { c ->
        val state = when (c.id) {
            in analysis.conflicts -> ConstraintRowState.Conflict
            in analysis.redundant -> ConstraintRowState.Redundant
            else -> ConstraintRowState.Ok
        }
        val names = c.elementIds.mapNotNull { doc.scene[it] }.map { ElementNames.nameOf(it) }
        val stateText = state.stateText.takeIf { state != ConstraintRowState.Ok }
        val label = SketchConstraintStrings.Panel.kindLabel(c.kind)
        val text = SketchConstraintStrings.Panel.rowText(label, ConstraintValueText.of(c, technical, format), names.joinToString(", "), stateText)
        ConstraintRow(c.id, text, state, c.reference, c.isDimensional, names, c.elementIds)
    }

    /** The rows that belong to the selected geometry: selecting geometry highlights its constraints, as a row highlights its geometry (UX-34). */
    fun touching(rows: List<ConstraintRow>, selected: Set<ElementId>): List<ConstraintRow> = rows.filter { r -> r.elementIds.any { it in selected } }

    /** True when the list has nothing to show: the empty state of UX-84 reads the same with or without a selection. */
    fun isEmpty(doc: SketchDocumentV2): Boolean = doc.constraints.isEmpty()
}

/** The value part of a dimension row or glyph: inches on a technical sheet, the bare number elsewhere, degrees for an angle. */
object ConstraintValueText {
    private const val HALF_TURN_DEGREES = 180.0

    /** [format] only matters for a metric display: the inch sheet keeps its mixed-inch spelling here whatever the pills show. */
    fun of(c: Constraint, technical: Boolean, format: LengthFormat = LengthFormat.INCHES_MIXED): String? {
        val value = c.value ?: return null
        return when {
            c.kind == ConstraintKind.Angle -> "${(value * HALF_TURN_DEGREES / PI).roundToLong()}°"
            technical -> if (MetricLength.isMetric(format)) MetricScene.formatUnits(value, format) else LengthFormatter.format(Inches(value.roundToLong()), LengthFormat.INCHES_MIXED)
            else -> value.roundToLong().toString()
        }
    }
}

/** One Constrain flyout entry for the current selection: enabled, or disabled with the reason to show (UX-37). */
data class ConstrainItemState(val item: ConstrainMenuItem, val enabled: Boolean, val reason: String?)

/** What the Constrain trigger and flyout show. [Hidden]: the target's solver tier hides constraint UI (ADR-003). */
sealed interface ConstrainFlyout {
    data object Hidden : ConstrainFlyout

    /** The trigger is disabled and the reason is available through touch help ("Select an element to constrain"). */
    data class Disabled(val reason: String) : ConstrainFlyout

    data class Open(val items: List<ConstrainItemState>) : ConstrainFlyout {
        val enabled: Set<ConstrainMenuItem> get() = items.filter { it.enabled }.mapTo(LinkedHashSet()) { it.item }
    }
}

/**
 * Which Constrain items the selection enables (Task 4.2.3b, UX-37). The flyout works for ONE or more selected elements:
 * single-element kinds (Horizontal, Vertical, Fixed, Distance as a line's length, Radius or Diameter on a circle) are enabled
 * for one element, the two-element kinds are shown disabled with their reason. Kinds the solver tier does not expose are not
 * listed at all (D-114), and the `Hidden` tier hides the whole flyout.
 */
object ConstrainFlyoutModel {
    private const val MAX_PERMUTED = 3

    fun of(selection: List<SceneElement>, tier: SolverTier, selectionHasDimension: Boolean = false): ConstrainFlyout {
        if (tier == SolverTier.Hidden) return ConstrainFlyout.Hidden
        if (selection.isEmpty()) return ConstrainFlyout.Disabled(SketchReasonText.SelectAnElement)
        val items = ConstrainMenuItem.entries.filter { exposed(it, tier) }.map { item -> stateOf(item, selection, selectionHasDimension) }
        return ConstrainFlyout.Open(items)
    }

    fun kindsOf(item: ConstrainMenuItem): List<ConstraintKind> = when (item) {
        ConstrainMenuItem.Coincident -> listOf(ConstraintKind.Coincident)
        ConstrainMenuItem.Horizontal -> listOf(ConstraintKind.Horizontal)
        ConstrainMenuItem.Vertical -> listOf(ConstraintKind.Vertical)
        ConstrainMenuItem.Parallel -> listOf(ConstraintKind.Parallel)
        ConstrainMenuItem.Perpendicular -> listOf(ConstraintKind.Perpendicular)
        ConstrainMenuItem.Equal -> listOf(ConstraintKind.Equal)
        ConstrainMenuItem.Tangent -> listOf(ConstraintKind.Tangent)
        ConstrainMenuItem.Symmetric -> listOf(ConstraintKind.Symmetric)
        ConstrainMenuItem.Fixed -> listOf(ConstraintKind.Fixed)
        ConstrainMenuItem.Distance -> listOf(ConstraintKind.Distance)
        ConstrainMenuItem.Angle -> listOf(ConstraintKind.Angle)
        ConstrainMenuItem.RadiusOrDiameter -> listOf(ConstraintKind.Radius, ConstraintKind.Diameter)
        ConstrainMenuItem.ReferenceToggle -> listOf(ConstraintKind.Distance, ConstraintKind.Angle, ConstraintKind.Radius, ConstraintKind.Diameter, ConstraintKind.DistanceX, ConstraintKind.DistanceY)
        ConstrainMenuItem.HorizontalDistance -> listOf(ConstraintKind.DistanceX)
        ConstrainMenuItem.VerticalDistance -> listOf(ConstraintKind.DistanceY)
    }

    private fun exposed(item: ConstrainMenuItem, tier: SolverTier): Boolean = kindsOf(item).any { SolverTierGate.exposes(tier, it) }

    private fun stateOf(item: ConstrainMenuItem, selection: List<SceneElement>, selectionHasDimension: Boolean): ConstrainItemState {
        if (item == ConstrainMenuItem.ReferenceToggle) {
            val notConstrainable = selection.firstNotNullOfOrNull { (ConstraintEligibility.reason(it, ConstraintKind.Distance) as? Eligibility.Ineligible)?.reason }
            if (notConstrainable != null) return ConstrainItemState(item, false, notConstrainable)
            return if (selectionHasDimension) ConstrainItemState(item, true, null) else ConstrainItemState(item, false, SketchReasonText.ReferenceNeedsDimension)
        }
        val reasons = kindsOf(item).map { reasonFor(it, selection) }
        return if (reasons.any { it == null }) ConstrainItemState(item, true, null) else ConstrainItemState(item, false, reasons.first())
    }

    /** Null when [kind] can be applied to [selection]; else why not. */
    private fun reasonFor(kind: ConstraintKind, selection: List<SceneElement>): String? {
        for (e in selection) {
            val verdict = ConstraintEligibility.reason(e, kind)
            if (verdict is Eligibility.Ineligible) return verdict.reason
        }
        return if (completes(kind, selection)) null else ConstraintEligibility.selectionReason(kind)
    }

    /** True when some order of the selected elements, each as a whole, is a complete operand list for [kind]. */
    private fun completes(kind: ConstraintKind, selection: List<SceneElement>): Boolean {
        if (selection.size > MAX_PERMUTED) return false
        return orders(selection).any { ConstraintParts.accepts(kind, it.map { e -> PartRef(e, TargetPart.Whole) }) }
    }

    private fun orders(items: List<SceneElement>): List<List<SceneElement>> {
        if (items.size <= 1) return listOf(items)
        return items.indices.flatMap { i -> orders(items.filterIndexed { j, _ -> j != i }).map { listOf(items[i]) + it } }
    }
}

/** What a conflict-card button does. */
enum class ConflictActionKind { RemoveNew, ReplaceExisting, MakeReference, ShowConflicts, RemoveConflicting }

/** [command] is what the host commits through `SolveRunner` (null for a button that only changes the view or does nothing). */
data class ConflictAction(val kind: ConflictActionKind, val label: String, val command: Command?)

/** The inline card of an over-constraining add or a sheet loaded with conflicts (UX-32): the message and its actions, Esc being [escape]. */
data class ConflictCard(val message: String, val actions: List<ConflictAction>, val conflictIds: List<ConstraintId>) {
    /** Esc counts as removing the new constraint (UX-32); a loaded-conflict card has no new constraint, so Esc just dismisses. */
    val escape: ConflictAction? get() = actions.firstOrNull { it.kind == ConflictActionKind.RemoveNew }
}

object ConflictCards {
    /** The card for [added] being refused with [rejection] against [base], the sheet as it was before the add. */
    fun forRejected(base: SketchDocumentV2, added: Constraint, rejection: SolveRejection.Unsatisfiable): ConflictCard {
        val partner = rejection.ids.firstNotNullOfOrNull { id -> base.constraints.firstOrNull { it.id == id && it.id != added.id } }
        val message = if (rejection.kind == UnsatisfiedKind.Conflict && partner != null) {
            val shared = partner.elementIds.firstOrNull { it in added.elementIds } ?: partner.elementIds.firstOrNull()
            val element = shared?.let { base.scene[it] }?.let { ElementNames.nameOf(it) }.orEmpty()
            SketchConstraintStrings.Panel.conflict(SketchConstraintStrings.Panel.kindLabel(added.kind), SketchConstraintStrings.Panel.kindLabel(partner.kind), element)
        } else {
            SketchConstraintStrings.Panel.NOT_SOLVED
        }
        val actions = buildList {
            add(ConflictAction(ConflictActionKind.RemoveNew, SketchConstraintStrings.Panel.REMOVE_NEW, null))
            val existing = DimensionAdd.replaceable(base, added, rejection.ids.map { it.value })
            val newValue = added.value
            if (existing != null && newValue != null) add(ConflictAction(ConflictActionKind.ReplaceExisting, SketchConstraintStrings.Panel.REPLACE_EXISTING, SetDimensionValue(existing, newValue)))
            if (added.isDimensional && !added.reference) {
                val asReference = SequenceCommand(listOf(AddConstraint(added.copy(reference = true)), SetConstraintReference(added.id, true)))
                add(ConflictAction(ConflictActionKind.MakeReference, SketchConstraintStrings.Panel.MAKE_REFERENCE, asReference))
            }
            add(ConflictAction(ConflictActionKind.ShowConflicts, SketchConstraintStrings.Panel.SHOW_CONFLICTS, null))
        }
        return ConflictCard(message, actions, rejection.ids)
    }

    /** The card for a sheet loaded with conflicting constraints (the only way to reach "Over-constrained"), or null when none conflict. */
    fun forLoaded(doc: SketchDocumentV2, analysis: ConstraintAnalysis): ConflictCard? {
        val clashing = doc.constraints.filter { it.id in analysis.conflicts }
        if (analysis.conflictCount == 0) return null
        val first = clashing.firstOrNull()
        val actions = buildList {
            add(ConflictAction(ConflictActionKind.ShowConflicts, SketchConstraintStrings.Panel.SHOW_CONFLICTS, null))
            if (first != null) add(ConflictAction(ConflictActionKind.RemoveConflicting, SketchConstraintStrings.Panel.REMOVE_CONFLICTING, RemoveConstraint(first.id)))
            clashing.firstOrNull { it.isDimensional && !it.reference }?.let {
                add(ConflictAction(ConflictActionKind.MakeReference, SketchConstraintStrings.Panel.MAKE_REFERENCE, SetConstraintReference(it.id, true)))
            }
        }
        return ConflictCard(SketchConstraintStrings.Panel.overConstrained(analysis.conflictCount), actions, clashing.map { it.id })
    }
}

/** Producer of the `WarningNotice` banner row: it only emits through the port and never imports the banner slot (Story 3.6.4a). */
object ConstraintWarnings {
    fun raise(port: DiagnosticsPort, card: ConflictCard) = port.raise(BannerRowId.WarningNotice, details = card.message)

    fun resolve(port: DiagnosticsPort) = port.resolve(BannerRowId.WarningNotice)
}
