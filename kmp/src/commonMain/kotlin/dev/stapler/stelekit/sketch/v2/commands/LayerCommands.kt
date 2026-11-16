// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.ValueError

// Layer commands (Story 1.3.2c, ADR-006). Layers are a sheet-level list; an element names its layer through
// `ElementBase.layerId`. The default layer always exists and cannot be deleted.

private fun SketchSceneV2.layerOrFail(id: LayerId): Either<CommandError, Layer> =
    layer(id)?.let { Either.Right(it) } ?: Either.Left(CommandError.UnknownLayer(id.value))

private fun SketchDocumentV2.withLayers(layers: List<Layer>): SketchDocumentV2 = copy(scene = scene.withLayers(layers))

/** Applies [change] to one layer; layers are not elements, so no element id is touched. */
private inline fun SketchDocumentV2.updateLayer(id: LayerId, change: (Layer) -> Layer): Either<CommandError, Applied> = either {
    val current = scene.layerOrFail(id).bind()
    Applied(withLayers(scene.layers.map { if (it.id == id) change(current) else it }), emptySet())
}

/** Adds a layer above the existing ones. */
data class AddLayer(val id: LayerId, val name: String) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val limits = doc.scene.limits
        if (id.value.isBlank() || id.value.length > limits.maxLayerIdChars) raise(CommandError.InvalidLayerId(id.value))
        if (name.length > limits.maxNameChars) raise(CommandError.NameTooLong(limits.maxNameChars))
        if (doc.scene.layer(id) != null) raise(CommandError.DuplicateLayer(id.value))
        if (doc.scene.layers.size >= limits.maxLayers) raise(CommandError.TooManyLayers(limits.maxLayers))
        val order = (doc.scene.layers.maxOfOrNull { it.order } ?: 0) + 1
        Applied(doc.withLayers(doc.scene.layers + Layer(id, name, order = order)), emptySet())
    }
}

data class RenameLayer(val id: LayerId, val name: String) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val limit = doc.scene.limits.maxNameChars
        if (name.length > limit) raise(CommandError.NameTooLong(limit))
        doc.updateLayer(id) { it.copy(name = name) }.bind()
    }
}

data class SetLayerVisible(val id: LayerId, val visible: Boolean) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = doc.updateLayer(id) { it.copy(visible = visible) }
}

data class SetLayerLocked(val id: LayerId, val locked: Boolean) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = doc.updateLayer(id) { it.copy(locked = locked) }
}

/** [opacity] is 0..1 and multiplies with element opacity. */
data class SetLayerOpacity(val id: LayerId, val opacity: Double) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        if (!opacity.isFinite()) raise(CommandError.ValueRejected(ValueError.NonFinite(opacity)))
        if (opacity < 0.0 || opacity > 1.0) raise(CommandError.ValueRejected(ValueError.OutOfRange(opacity, 1.0)))
        doc.updateLayer(id) { it.copy(opacity = opacity) }.bind()
    }
}

/** Moves a layer to position [toIndex] (0 is the bottom) in layer order and renumbers `order` as 0, 1, 2 and so on. */
data class MoveLayer(val id: LayerId, val toIndex: Int) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val layer = doc.scene.layerOrFail(id).bind()
        val sorted = doc.scene.layers.sortedBy { it.order }.filter { it.id != id }.toMutableList()
        sorted.add(toIndex.coerceIn(0, sorted.size), layer)
        val renumbered = sorted.mapIndexed { i, l -> l.copy(order = i) }
        val byId = renumbered.associateBy { it.id }
        // Keep the stored list order so a no-op move is an exact no-change.
        Applied(doc.withLayers(doc.scene.layers.map { byId.getValue(it.id) }), emptySet())
    }
}

/** Moves [ids] to the layer [target], on top of it, keeping their relative paint order. */
data class MoveElementsToLayer(val ids: List<ElementId>, val target: LayerId) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        doc.scene.layerOrFail(target).bind()
        val wanted = ids.toSet()
        ids.firstOrNull { it !in doc.scene }?.let { raise(CommandError.UnknownElement(it.value)) }
        var out = doc.scene
        val changed = LinkedHashSet<ElementId>()
        var key = topKey(doc.scene, target)
        for (e in effectiveOrder(doc.scene).filter { it.id in wanted && it.base.layerId != target }) {
            out = out.replace(e.withBase(e.base.copy(layerId = target, zKey = key)).touched()).bind()
            changed += e.id
            key = ZKeys.after(key)
        }
        finish(doc, out, changed)
    }
}

/** Deletes a layer; its elements move to [into] (the default layer unless told otherwise). */
data class DeleteLayer(val id: LayerId, val into: LayerId = LayerId.Default) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        if (id == LayerId.Default) raise(CommandError.DefaultLayerProtected)
        doc.scene.layerOrFail(id).bind()
        doc.scene.layerOrFail(into).bind()
        val members = doc.scene.elementsInOrder.filter { it.base.layerId == id }.map { it.id }
        val moved = MoveElementsToLayer(members, into).apply(doc).bind()
        val scene = moved.doc.scene
        Applied(moved.doc.withLayers(scene.layers.filter { it.id != id }), moved.changedIds)
    }
}
