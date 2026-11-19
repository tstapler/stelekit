// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.GroupId

// Group and frame commands (Story 1.3.2b). `groupIds` is innermost first, so a new group is appended and an ungroup
// removes the last entry. `reconcile` removes a group that ends up with a single member.

private const val MIN_GROUP_MEMBERS = 2

/** Wraps [ids] in the new group [groupId], outside any group they already share. */
data class GroupElements(val ids: List<ElementId>, val groupId: GroupId) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val members = doc.scene.requireAllForGroup(ids).bind()
        if (members.size < MIN_GROUP_MEMBERS) raise(CommandError.GroupTooSmall(members.size))
        if (doc.scene.elementsInOrder.any { groupId in it.base.groupIds }) raise(CommandError.DuplicateGroup(groupId.value))
        var out = doc.scene
        for (e in members) out = out.replace(e.withBase(e.base.copy(groupIds = e.base.groupIds + groupId)).touched()).bind()
        finish(doc, out, members.mapTo(LinkedHashSet()) { it.id })
    }
}

/** Removes only the outermost group (the last entry of `groupIds`) of each of [ids]; no other element is touched (Excalidraw-like). */
data class UngroupElements(val ids: List<ElementId>) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        var out = doc.scene
        val changed = LinkedHashSet<ElementId>()
        for (e in doc.scene.requireAllForGroup(ids).bind()) {
            if (e.base.groupIds.isEmpty()) continue
            out = out.replace(e.withBase(e.base.copy(groupIds = e.base.groupIds.dropLast(1))).touched()).bind()
            changed += e.id
        }
        finish(doc, out, changed)
    }
}

/** Makes [ids] members of the frame [frameId]; a frame itself cannot be a member. */
data class AssignToFrame(val frameId: ElementId, val ids: List<ElementId>) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val frame = doc.scene[frameId] ?: raise(CommandError.UnknownElement(frameId.value))
        if (frame !is FrameElement) raise(CommandError.NotAFrame(frameId.value))
        var out = doc.scene
        val changed = LinkedHashSet<ElementId>()
        for (e in doc.scene.requireAllForGroup(ids).bind()) {
            if (e is FrameElement) raise(CommandError.NotAFrame(e.id.value))
            if (e.base.frameId == frameId) continue
            out = out.replace(e.withBase(e.base.copy(frameId = frameId)).touched()).bind()
            changed += e.id
        }
        finish(doc, out, changed)
    }
}

/** Takes [ids] out of whatever frame holds them. */
data class RemoveFromFrame(val ids: List<ElementId>) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        var out = doc.scene
        val changed = LinkedHashSet<ElementId>()
        for (e in doc.scene.requireAllForGroup(ids).bind()) {
            if (e.base.frameId == null) continue
            out = out.replace(e.withBase(e.base.copy(frameId = null)).touched()).bind()
            changed += e.id
        }
        finish(doc, out, changed)
    }
}

private fun dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2.requireAllForGroup(ids: Collection<ElementId>) = either<CommandError, List<dev.stapler.stelekit.sketch.v2.scene.SceneElement>> {
    ids.distinct().map { this@requireAllForGroup[it] ?: raise(CommandError.UnknownElement(it.value)) }
}
