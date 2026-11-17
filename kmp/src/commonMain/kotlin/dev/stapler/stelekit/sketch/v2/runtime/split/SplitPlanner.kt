// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.split

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.commands.DeleteElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.reconcile

/** Why a split is refused before anything is written. */
sealed interface SplitRefusal {
    data object NothingSelected : SplitRefusal

    /** [id] is not an element of the source sheet. */
    data class UnknownElement(val id: String) : SplitRefusal

    /** The moved elements do not fit a new sheet (cannot happen below the cap; kept so a corrupt input never throws). */
    data object DoesNotFit : SplitRefusal
}

/**
 * The pure half of "Split into a new sketch" (Story 2.2.4): the moved elements as a document of their own, and the
 * [deleteMoved] command that removes them from the source as ONE history step, so undo in the source's own `HistoryV2`
 * brings them back. [sourceAfter] is that command's result, which the source file is written with.
 *
 * Moved: the selected elements and the labels bound to them. Ids are kept. A binding or frame that pointed at an element that
 * stays behind is cleared by `reconcile` and counted in [clearedBindings]; a constraint is carried only when every operand moved
 * ([carriedConstraints]), and one that spans both sides is dropped from both ([droppedConstraints]).
 */
class SplitPlan(
    val movedIds: List<ElementId>,
    val newDocument: SketchDocumentV2,
    val sourceAfter: SketchDocumentV2,
    val deleteMoved: DeleteElements,
    val clearedBindings: Int,
    val carriedConstraints: Int,
    val droppedConstraints: Int,
)

object SplitPlanner {
    fun plan(
        source: SketchDocumentV2,
        selected: Set<ElementId>,
        newSheetUuid: String,
        limits: SceneLimits = SceneLimits.Default,
    ): Either<SplitRefusal, SplitPlan> {
        if (selected.isEmpty()) return SplitRefusal.NothingSelected.left()
        selected.firstOrNull { it !in source.scene }?.let { return SplitRefusal.UnknownElement(it.value).left() }
        val moved = closure(source.scene, selected)
        val movedOrdered = source.scene.elementsInOrder.filter { it.id in moved }

        var scene = SketchSceneV2.empty(limits).withLayers(source.scene.layers)
        for (element in movedOrdered) scene = scene.add(element).getOrNull() ?: return SplitRefusal.DoesNotFit.left()
        val repaired = reconcile(scene)

        val carried = source.constraints.filter { c -> c.elementIds.all { it in moved } }
        val dropped = source.constraints.count { c -> c.elementIds.any { it in moved } && c !in carried }
        val delete = DeleteElements(movedOrdered.map { it.id })
        val after = Transaction.begin(source).apply(delete).getOrNull()?.doc ?: return SplitRefusal.DoesNotFit.left()
        val newDocument = SketchDocumentV2(
            scene = repaired.scene,
            sheetUuid = newSheetUuid,
            constraints = carried,
            nameCounters = source.nameCounters,
        )
        return SplitPlan(movedOrdered.map { it.id }, newDocument, after, delete, repaired.reasons.size, carried.size, dropped).right()
    }

    /** The selection plus the text labels bound to a selected container. */
    private fun closure(scene: SketchSceneV2, selected: Set<ElementId>): Set<ElementId> {
        val out = LinkedHashSet(selected)
        scene.elementsInOrder.filter { it is TextElement && it.containerId in selected }.forEach { out += it.id }
        return out
    }
}
