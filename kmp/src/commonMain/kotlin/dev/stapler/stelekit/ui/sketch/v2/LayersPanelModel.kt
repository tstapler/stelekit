// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.AddLayer
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.DeleteLayerAndElements
import dev.stapler.stelekit.sketch.v2.commands.LockElements
import dev.stapler.stelekit.sketch.v2.commands.MoveLayer
import dev.stapler.stelekit.sketch.v2.commands.MoveToBaseThenDelete
import dev.stapler.stelekit.sketch.v2.commands.SetLayerLocked
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.UnderlayIds

/** One row of the layers panel, top of the paint order first. */
data class LayerRowModel(
    val layer: Layer,
    val elementCount: Int,
    val active: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    /** Null when the layer can be deleted; else the reason shown beside the disabled action. */
    val deleteDisabledReason: String?,
) {
    /** What a screen reader reads for the visibility switch (UX-17); the role adds "switch". */
    val visibilityLabel: String get() = "Layer ${layer.name}, ${if (layer.visible) "visible" else "hidden"}"

    /** What a screen reader reads for the lock switch (UX-17). */
    val lockLabel: String get() = "Layer ${layer.name}, ${if (layer.locked) "locked" else "unlocked"}"
}

/** The delete-layer confirmation (UX-18). */
data class DeleteLayerDialog(val layer: Layer, val elementCount: Int) {
    val title: String get() = "Delete layer '${layer.name}' and its $elementCount elements?"
    val deleteAll: Command get() = DeleteLayerAndElements(layer.id)
    val moveToBase: Command get() = MoveToBaseThenDelete(layer.id)

    companion object {
        const val DELETE_ALL_LABEL = "Delete layer and elements"
        const val MOVE_LABEL = "Move elements to Base then delete"
        const val CANCEL_LABEL = "Cancel"
    }
}

/** Selection and announcements after a layer change (UX-13, UX-86). */
data class SelectionOutcome(val selection: Set<ElementId>, val announcement: String?)

object LayersPanelModel {
    const val RENAME_COLLISION = "A layer with this name exists"
    const val BASE_NOT_DELETABLE = "The base layer cannot be deleted"
    const val PANEL_TITLE = "Layers"

    /** The panel stays hidden until a second layer exists; the overflow item "Layers" creates it (UX-19). */
    fun isVisible(scene: SketchSceneV2): Boolean = scene.layers.size > 1

    fun rows(scene: SketchSceneV2, activeLayer: LayerId): List<LayerRowModel> {
        val ascending = scene.layers.sortedBy { it.order }
        val counts = scene.elementsInOrder.groupingBy { it.base.layerId }.eachCount()
        return ascending.mapIndexed { i, layer ->
            LayerRowModel(
                layer = layer,
                elementCount = counts[layer.id] ?: 0,
                active = layer.id == activeLayer,
                canMoveUp = i < ascending.lastIndex && layer.id != UnderlayIds.LAYER,
                canMoveDown = i > pinnedBottom(ascending) && layer.id != UnderlayIds.LAYER,
                deleteDisabledReason = if (layer.id == LayerId.Default) BASE_NOT_DELETABLE else null,
            )
        }.asReversed()
    }

    /** 1 when the underlay layer sits at the bottom (nothing may go below it), else 0. */
    private fun pinnedBottom(ascending: List<Layer>): Int = if (ascending.firstOrNull()?.id == UnderlayIds.LAYER) 1 else 0

    /** A layer always stays active: when the active one is gone the base layer takes over. */
    fun activeOrBase(scene: SketchSceneV2, active: LayerId): LayerId = if (scene.layer(active) != null) active else LayerId.Default

    fun moveUp(scene: SketchSceneV2, id: LayerId): Command? = move(scene, id, +1)

    fun moveDown(scene: SketchSceneV2, id: LayerId): Command? = move(scene, id, -1)

    private fun move(scene: SketchSceneV2, id: LayerId, step: Int): Command? {
        val ascending = scene.layers.sortedBy { it.order }
        val at = ascending.indexOfFirst { it.id == id }
        val to = at + step
        val floor = pinnedBottom(ascending)
        val movable = id != UnderlayIds.LAYER
        return if (at < 0 || !movable || to !in floor until ascending.size) null else MoveLayer(id, to)
    }

    /** "Layer 2" and so on: the first name no layer uses. */
    fun nextLayerName(scene: SketchSceneV2): String {
        val used = scene.layers.mapTo(HashSet()) { it.name }
        var n = scene.layers.size + 1
        while ("Layer $n" in used) n++
        return "Layer $n"
    }

    fun newLayer(scene: SketchSceneV2, id: LayerId): Command = AddLayer(id, nextLayerName(scene))

    /** The dialog for [id], or null when the layer is empty (it is then deleted without asking) or cannot be deleted. */
    fun deleteDialog(scene: SketchSceneV2, id: LayerId): DeleteLayerDialog? {
        val layer = scene.layer(id) ?: return null
        val count = scene.elementsInOrder.count { it.base.layerId == id }
        return if (id == LayerId.Default || count == 0) null else DeleteLayerDialog(layer, count)
    }

    /** Text for a failed layer command; null for an error the panel has no special text for. */
    fun errorText(error: CommandError): String? = when (error) {
        is CommandError.DuplicateLayerName -> RENAME_COLLISION
        is CommandError.DefaultLayerProtected -> BASE_NOT_DELETABLE
        is CommandError.NameTooLong -> "Names can have up to ${error.limit} characters"
        is CommandError.TooManyLayers -> "A sheet can have up to ${error.limit} layers"
        else -> null
    }
}

/** The one selectability rule (ux.md section 4, UX-12): what the canvas and the Elements list may select. */
object LayerSelectionRules {
    enum class Source { Canvas, ElementsList }

    fun isSelectable(scene: SketchSceneV2, element: SceneElement, source: Source): Boolean {
        val layer = scene.layer(element.base.layerId)
        if (layer != null && !layer.visible) return false
        val locked = element.base.locked || layer?.locked == true
        return source == Source.ElementsList || !locked
    }

    /** Hiding [hidden] drops its elements from [selection] and says so (UX-13). */
    fun afterHide(scene: SketchSceneV2, selection: Set<ElementId>, hidden: LayerId): SelectionOutcome {
        val dropped = selection.filter { scene[it]?.base?.layerId == hidden }
        if (dropped.isEmpty()) return SelectionOutcome(selection, null)
        val name = scene.layer(hidden)?.name ?: hidden.value
        val noun = if (dropped.size == 1) "1 element" else "${dropped.size} elements"
        return SelectionOutcome(selection - dropped.toSet(), "Selection cleared: layer $name hidden ($noun deselected)")
    }
}

/** One action of the layer toasts, with its tested effect and announcement (Task 3.5.2d, UX-86). */
data class LayerNoticeAction(val label: String, val announcement: String, val command: Command?, val activeLayer: LayerId?)

object LayerNotices {
    /** [Unlock] on the `LayerLocked` toast ("Locked layer: Walls."). */
    fun unlock(layer: Layer) = LayerNoticeAction("Unlock", "Layer ${layer.name} unlocked", SetLayerLocked(layer.id, false), null)

    /** [Switch layer] under More on the `LayerLocked` toast: makes [target] the layer new elements go to. */
    fun switchLayer(target: Layer) = LayerNoticeAction("Switch layer", "Active layer: ${target.name}", null, target.id)

    /** [Unlock all] on the `LockedSkipped` toast: unlocks the elements an edit skipped. */
    fun unlockAll(skipped: List<ElementId>): LayerNoticeAction {
        val noun = if (skipped.size == 1) "1 locked element" else "${skipped.size} locked elements"
        return LayerNoticeAction("Unlock all", "Unlocked $noun", LockElements(skipped, false), null)
    }

    /** The Details line of the `LockedSkipped` toast. */
    fun skippedDetails(count: Int): String = "$count locked ${if (count == 1) "element was" else "elements were"} not changed"
}
