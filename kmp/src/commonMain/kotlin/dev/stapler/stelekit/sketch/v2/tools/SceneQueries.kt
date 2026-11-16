// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2

/** The one selectability rule (UX-12): the canvas never selects or edits a locked element or one on a hidden or locked layer. */
object Selectability {
    fun isSelectable(scene: SketchSceneV2, e: SceneElement): Boolean {
        val layer = scene.layer(e.base.layerId) ?: Layer.DefaultLayer
        return layer.visible && !layer.locked && !e.base.locked
    }

    /** Every element a Select all may pick, in paint order. */
    fun selectable(scene: SketchSceneV2): List<SceneElement> = scene.elementsInOrder.filter { isSelectable(scene, it) }
}

/** Frame membership rules shared by the Frame tool and the Select tool's drop (Task 3.2.3b). */
object FrameMembership {
    private fun contains(outer: Bounds, inner: Bounds): Boolean =
        inner.minX >= outer.minX && inner.maxX <= outer.maxX && inner.minY >= outer.minY && inner.maxY <= outer.maxY

    /** Elements that are not frames and lie fully inside [frame]; those already in another frame are left there. */
    fun adoptedBy(scene: SketchSceneV2, frame: Bounds, exclude: Set<ElementId> = emptySet()): List<ElementId> =
        scene.elementsInOrder
            .filter { it !is FrameElement && it.id !in exclude && it.base.frameId == null && Selectability.isSelectable(scene, it) }
            .filter { contains(frame, ElementBounds.geometry(it)) }
            .map { it.id }

    /**
     * What dragging [moved] by ([dx], [dy]) does to membership: a dropped element fully inside the topmost frame joins
     * it, one that no longer fits inside its frame leaves it. Members carried along by a moved frame keep their frame.
     */
    fun changesAfterMove(scene: SketchSceneV2, moved: Set<ElementId>, dx: Double, dy: Double): Map<ElementId, ElementId?> {
        val frames = scene.elementsInOrder.filterIsInstance<FrameElement>().filter { it.id !in moved }
        val out = LinkedHashMap<ElementId, ElementId?>()
        for (id in moved) {
            val e = scene[id] ?: continue
            if (e is FrameElement || e.base.frameId in moved) continue
            val b = ElementBounds.geometry(e)
            val shifted = Bounds(b.minX + dx, b.minY + dy, b.maxX + dx, b.maxY + dy)
            val target = frames.lastOrNull { contains(ElementBounds.geometry(it), shifted) }?.id
            if (target != e.base.frameId) out[id] = target
        }
        return out
    }
}
