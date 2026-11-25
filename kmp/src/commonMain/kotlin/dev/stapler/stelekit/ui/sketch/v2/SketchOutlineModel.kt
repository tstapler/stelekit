// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchOutlineStrings

/** One line of the Sketch outline: [position] is 1-based in reading order; [label] is the canvas semantics label (name, kind, size, "locked"). */
data class OutlineEntry(
    val id: ElementId,
    val position: Int,
    val total: Int,
    val label: String,
    val bounds: SceneBounds,
    val selected: Boolean,
    val locked: Boolean,
) {
    /** What a screen reader speaks for the row. */
    val spoken: String get() = SketchOutlineStrings.row(position, total, label, selected)
}

/**
 * The reading-order list of a sheet for screen-reader and switch users (E12). Reading order is top to bottom, then left to
 * right, by the top-left corner of each element's bounds; elements that tie keep paint order. Elements of a hidden layer and
 * labels bound to a container are left out, as in the canvas semantics, so the two never disagree.
 */
object SketchOutlineModel {
    fun build(scene: SketchSceneV2, selection: Set<ElementId>, annotations: Map<ElementId, String> = emptyMap()): List<OutlineEntry> {
        val layers = scene.layers.associateBy { it.id }
        val ordered = scene.elementsInOrder
            .filter { SceneSemantics.isNode(it, layers) }
            .mapIndexedNotNull { paint, e -> SceneBounds.of(e)?.let { Triple(paint, e, it) } }
            .sortedWith(compareBy({ it.third.minY }, { it.third.minX }, { it.first }))
        return ordered.mapIndexed { index, (_, e, bounds) ->
            OutlineEntry(
                id = e.id,
                position = index + 1,
                total = ordered.size,
                label = SceneSemantics.label(scene, e, annotations),
                bounds = bounds,
                selected = e.id in selection,
                locked = e.base.locked || layers[e.base.layerId]?.locked == true,
            )
        }
    }
}
