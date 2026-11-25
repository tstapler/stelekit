// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.merge

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ReconcileReason

/** Why a merge was not attempted or not possible; the caller keeps the conflict copy (ADR-008 section 7). Carries ids and tokens, never user text. */
sealed interface MergeRefusal {
    /** The two files are not the same sheet. */
    data object SheetMismatch : MergeRefusal

    /** The merged sheet would hold more elements, constraints or layers than the engine allows. */
    data class CapacityExceeded(val what: String) : MergeRefusal

    /** A merged element could not be written as JSON (a non-finite number), so it cannot be saved. */
    data class Unencodable(val where: String) : MergeRefusal
}

/** What kind of disagreement a [MergeConflict] settled. */
enum class ConflictKind {
    /** Both sides changed the same field of one element to different values; the greater canonical value won. */
    SameField,

    /** One side deleted an element the other edited; the edit won, so nothing the user drew is lost. */
    DeleteVsEdit,

    /** Both sides added an element with the same id and different content. */
    AddAdd,

    /** The element kind differs between the sides, so the element is taken whole from one side. */
    TypeChange,

    /** Field-wise merging produced an element the model refuses; it is taken whole from one side. */
    Unbuildable,

    /** Both sides changed the same layer, constraint or sheet setting to different values. */
    Other,
}

data class MergeConflict(val id: String, val kind: ConflictKind, val fields: List<String> = emptyList())

/** A change the merge made so the result is consistent; never a crash and never silent (the notice counts them). */
sealed interface MergeRepair {
    /** An opening whose host wall is gone was dropped. */
    data class OpeningDropped(val id: ElementId, val host: ElementId) : MergeRepair

    /** A constraint whose operand is gone was dropped. */
    data class ConstraintDropped(val id: ConstraintId) : MergeRepair

    /** An element's layer is gone; it moved to the default layer. */
    data class MovedToDefaultLayer(val id: ElementId) : MergeRepair

    /** The same repair `reconcile` makes after any edit (dangling binding, container, frame or group). */
    data class Reconciled(val reason: ReconcileReason) : MergeRepair
}

/**
 * What a merge did, relative to the OURS side (the document the merging device held): [added] came only from theirs,
 * [changed] differ from ours, [removed] were deleted by theirs. Every list is in id order, so the report is as
 * deterministic as the merged document.
 */
data class MergeReport(
    val added: List<ElementId>,
    val changed: List<ElementId>,
    val removed: List<ElementId>,
    val conflicts: List<MergeConflict>,
    val repairs: List<MergeRepair>,
) {
    /** Elements of the merged document that differ from ours; what "Show" selects. */
    val shownIds: List<ElementId> get() = (added + changed).sortedBy { it.value }

    /** The N of "Merged changes from another device (N elements)". */
    val affectedCount: Int get() = added.size + changed.size + removed.size

    val isEmpty: Boolean get() = affectedCount == 0 && repairs.isEmpty()
}

data class MergeResult(val doc: SketchDocumentV2, val report: MergeReport)
