// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.SceneElement

/**
 * Adds an imported scene to a sheet as ONE command, so one transaction commits it and one undo removes it (Story 5.1.2c).
 * It adds the [layers] the file named (up to `SceneLimits.maxLayers`; elements of a layer that does not fit land on the
 * default layer), gives an element whose id is already on the sheet a fresh id, and then pastes: references between the
 * imported elements are remapped, new ordinals are assigned in paint order, and the result is reconciled. A sheet
 * already at `SceneLimits.maxElements` refuses the whole import and changes nothing.
 */
data class ImportScene(val elements: List<SceneElement>, val layers: List<Layer> = emptyList()) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val withLayers = doc.copy(scene = doc.scene.withLayers(mergedLayers(doc)))
        PasteElements(elements, idMap(doc)).apply(withLayers).bind()
    }

    private fun mergedLayers(doc: SketchDocumentV2): List<Layer> {
        val existing = doc.scene.layers
        val limits = doc.scene.limits
        val names = existing.mapTo(HashSet()) { it.name }
        var next = (existing.maxOfOrNull { it.order } ?: 0) + 1
        val out = existing.toMutableList()
        for (layer in layers) {
            if (out.size >= limits.maxLayers) break
            if (out.any { it.id == layer.id }) continue
            var name = layer.name.take(limits.maxNameChars)
            while (name in names) name = (name.take(limits.maxNameChars - IMPORTED_SUFFIX.length) + IMPORTED_SUFFIX)
            names += name
            out += layer.copy(name = name, order = next++)
        }
        return out
    }

    /** Identity for ids the sheet does not hold; a colliding id becomes `<id>~i<n>` with the smallest free n. */
    private fun idMap(doc: SketchDocumentV2): Map<ElementId, ElementId> {
        val taken = HashSet<String>().apply { doc.scene.order.forEach { add(it.value) } }
        val incoming = elements.mapTo(HashSet()) { it.id.value }
        val out = LinkedHashMap<ElementId, ElementId>()
        for (e in elements) {
            var candidate = e.id.value
            var n = 1
            while (candidate in taken || (candidate != e.id.value && candidate in incoming)) candidate = "${e.id.value}~i${n++}"
            taken += candidate
            out[e.id] = ElementId(candidate)
        }
        return out
    }

    private companion object {
        const val IMPORTED_SUFFIX = " (imported)"
    }
}
