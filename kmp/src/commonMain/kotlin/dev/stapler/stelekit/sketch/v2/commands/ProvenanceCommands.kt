// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.provenance
import dev.stapler.stelekit.sketch.v2.scene.withProvenance

/**
 * Marks the agent-written elements [ids] as reviewed (epic E8). The marker stays, with `accepted` set, so the sheet still says an
 * agent wrote it; only the "needs review" ghost goes. Elements that carry no marker, or are accepted already, are skipped;
 * when nothing changes the command is refused with [CommandError.NoChange] so history records nothing.
 */
data class AcceptProvenance(val ids: List<ElementId>) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        var scene = doc.scene
        val changed = LinkedHashSet<ElementId>()
        for (id in ids.distinct()) {
            val element = scene[id] ?: raise(CommandError.UnknownElement(id.value))
            val accepted = element.accepted() ?: continue
            scene = scene.replace(accepted).bind()
            changed += id
        }
        if (changed.isEmpty()) raise(CommandError.NoChange)
        Applied(doc.copy(scene = scene), changed)
    }
}

/** Accepts everything an agent wrote and nobody reviewed, as one undo step. */
data object AcceptAllProvenance : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> =
        AcceptProvenance(doc.scene.elementsInOrder.filter { it.base.provenance?.needsReview == true }.map { it.id }).apply(doc)
}

private fun SceneElement.accepted(): SceneElement? {
    val marker = base.provenance?.takeIf { it.needsReview } ?: return null
    return withBase(base.withProvenance(marker.copy(accepted = true)).copy(version = base.version + 1))
}
