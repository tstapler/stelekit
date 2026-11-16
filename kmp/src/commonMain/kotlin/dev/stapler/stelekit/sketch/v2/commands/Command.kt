// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.reconcile

/**
 * The unit a history snapshot holds. Today it is the scene; sheet settings, constraints and the view join it in the
 * stories that introduce them, which is why history and commands take this type and not a bare scene.
 */
data class SketchDocumentV2(val scene: SketchSceneV2 = SketchSceneV2.empty())

/** A command's result: the new document and the ids of the elements it touched (drives caches and the spatial index). */
data class Applied(val doc: SketchDocumentV2, val changedIds: Set<ElementId>)

/** One edit. Pure: the same document and command always give the same result, and a failure changes nothing. */
fun interface Command {
    fun apply(doc: SketchDocumentV2): Either<CommandError, Applied>
}

/** One element's change in a [DocPatch]; `null` on a side means the element is absent there. */
data class ElementPatch(val id: ElementId, val before: SceneElement?, val after: SceneElement?)

/**
 * What a committed transaction did, recorded as data so redo replays it verbatim (no command is re-run and no solver
 * is re-solved). Applying it checks that the document still holds each `before` value, so a stale patch is refused
 * with [CommandError.PatchConflict] instead of overwriting newer work.
 */
data class DocPatch(
    val elements: List<ElementPatch>,
    val orderBefore: List<ElementId>,
    val orderAfter: List<ElementId>,
    val layersBefore: List<Layer>,
    val layersAfter: List<Layer>,
) {
    val isEmpty: Boolean get() = elements.isEmpty() && orderBefore == orderAfter && layersBefore == layersAfter

    val changedIds: Set<ElementId> get() = elements.mapTo(LinkedHashSet()) { it.id }

    fun forward(doc: SketchDocumentV2): Either<CommandError, SketchDocumentV2> = replay(doc, reversed = false)

    fun backward(doc: SketchDocumentV2): Either<CommandError, SketchDocumentV2> = replay(doc, reversed = true)

    // kibitzer:ignore flag-argument -- private helper shared by forward/backward; the two public entry points are the named split
    private fun replay(doc: SketchDocumentV2, reversed: Boolean): Either<CommandError, SketchDocumentV2> = either {
        fun from(p: ElementPatch) = if (reversed) p.after else p.before
        fun to(p: ElementPatch) = if (reversed) p.before else p.after
        val order = if (reversed) orderAfter else orderBefore
        val layers = if (reversed) layersAfter else layersBefore
        if (doc.scene.order != order) raise(CommandError.PatchConflict("order"))
        if (doc.scene.layers != layers) raise(CommandError.PatchConflict("layers"))
        for (p in elements) if (doc.scene[p.id] != from(p)) raise(CommandError.PatchConflict(p.id.value))

        var scene = doc.scene
        for (p in elements) if (to(p) == null) scene = scene.remove(p.id).bind()
        for (p in elements) {
            val target = to(p) ?: continue
            scene = (if (from(p) == null) scene.add(target) else scene.replace(target)).bind()
        }
        scene = scene.reorder(if (reversed) orderBefore else orderAfter).bind()
        SketchDocumentV2(scene.withLayers(if (reversed) layersBefore else layersAfter))
    }

    companion object {
        /** The patch that turns [before] into [after]. */
        fun diff(before: SketchDocumentV2, after: SketchDocumentV2): DocPatch {
            val ids = LinkedHashSet<ElementId>().apply { addAll(before.scene.order); addAll(after.scene.order) }
            val patches = ids.mapNotNull { id ->
                val b = before.scene[id]
                val a = after.scene[id]
                if (a == b) null else ElementPatch(id, b, a)
            }
            return DocPatch(patches, before.scene.order, after.scene.order, before.scene.layers, after.scene.layers)
        }
    }
}

/** A finished transaction: one history step. [before] and [after] share structure with every other snapshot. */
data class Committed(val before: SketchDocumentV2, val after: SketchDocumentV2, val patch: DocPatch) {
    val changedIds: Set<ElementId> get() = patch.changedIds
}

/**
 * A group of commands that commits as one history step (one gesture, one undo). Immutable: [apply] returns the next
 * transaction and a failing command leaves this one usable, so a half-applied gesture cannot exist. After every
 * command the scene runs through `reconcile`, and what it repairs belongs to the same step.
 */
class Transaction private constructor(val base: SketchDocumentV2, val doc: SketchDocumentV2, val changedIds: Set<ElementId>) {

    fun apply(command: Command): Either<CommandError, Transaction> = either {
        val applied = command.apply(doc).bind()
        val repaired = reconcile(applied.doc.scene)
        Transaction(base, SketchDocumentV2(repaired.scene), changedIds + applied.changedIds + repaired.changed)
    }

    /** Finishes the transaction; [CommandError.NoChange] when the document ended up equal to where it began. */
    fun commit(): Either<CommandError, Committed> {
        val patch = DocPatch.diff(base, doc)
        return if (patch.isEmpty) CommandError.NoChange.left() else Committed(base, doc, patch).right()
    }

    companion object {
        fun begin(doc: SketchDocumentV2): Transaction = Transaction(doc, doc, emptySet())
    }
}
