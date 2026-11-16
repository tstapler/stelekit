// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.LockElements
import dev.stapler.stelekit.sketch.v2.commands.RenameElement
import dev.stapler.stelekit.sketch.v2.commands.SetLayerVisible
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.view.RevealResult
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import dev.stapler.stelekit.sketch.v2.view.ScreenRect
import dev.stapler.stelekit.sketch.v2.view.SelectionReveal
import dev.stapler.stelekit.sketch.v2.view.SketchViewport

/** One row of the Elements tab (Story 3.5.7, UX-110): a layer header or an element of that layer. */
sealed interface ElementsRow {
    /** A stable list key. */
    val key: String

    data class LayerHeader(val layer: Layer, val elementCount: Int, val expanded: Boolean) : ElementsRow {
        override val key: String get() = "layer:${layer.id.value}"
        val description: String get() = "Layer ${layer.name}, $elementCount elements, ${if (expanded) "expanded" else "collapsed"}"
    }

    data class Item(
        val element: SceneElement,
        val layer: Layer,
        val name: String,
        val kind: String,
        /** The text of a text element or of the label bound to a shape; empty when there is none. */
        val textLabel: String,
        val locked: Boolean,
        val layerHidden: Boolean,
    ) : ElementsRow {
        override val key: String get() = "element:${element.id.value}"

        /** Dimmed and not selectable from the list (UX-12): the element sits on a hidden layer. */
        val dimmed: Boolean get() = layerHidden

        /** "locked" and "(hidden)" as text, never only an icon or a colour. */
        val stateText: String
            get() = listOfNotNull(if (locked) LOCKED_TEXT else null, if (layerHidden) HIDDEN_TEXT else null).joinToString(" ")

        /** One merged node for a screen reader: name, kind, label and state. */
        val description: String
            get() = listOfNotNull(name, kind.takeIf { !name.startsWith(it) }, textLabel.takeIf { it.isNotEmpty() }, stateText.takeIf { it.isNotEmpty() }).joinToString(", ")
    }

    companion object {
        const val LOCKED_TEXT = "locked"
        const val HIDDEN_TEXT = "(hidden)"
    }
}

/** What activating a row does: select (and pan to) the element, or offer the way out of a state that blocks it. */
sealed interface RowOutcome {
    /** Select [id] and pan to it; [unlock] is offered beside a locked element. */
    data class Select(val id: ElementId, val unlock: Command?) : RowOutcome

    /** The element's layer is hidden: nothing is selected and [showLayer] is the offered action. */
    data class LayerHidden(val showLayer: Command) : RowOutcome
}

/** The rows, selection rules and rename of the Elements tab (Story 3.5.7b). Pure over a [SketchSceneV2]. */
object ElementsPanelModel {
    const val TAB_LABEL = "Elements"
    const val NAME_TAKEN = "An element with this name exists"
    const val EMPTY = "Nothing on this sheet yet"
    private const val LABEL_PREVIEW_CHARS = 40

    /**
     * The layers top-of-paint-order first as expandable groups, each holding its elements topmost first. A layer in
     * [collapsed] shows only its header. Hidden-layer elements are listed (dimmed) so they can still be found; a label bound to a shape shows as that shape's text, not as a row.
     */
    fun rows(scene: SketchSceneV2, collapsed: Set<LayerId> = emptySet()): List<ElementsRow> {
        val labels = boundLabels(scene)
        val byLayer = scene.elementsInOrder.withIndex().groupBy({ it.value.base.layerId }, { it })
        val out = ArrayList<ElementsRow>()
        for (layer in scene.layers.sortedByDescending { it.order }) {
            val inLayer = byLayer[layer.id].orEmpty().filterNot { (it.value as? TextElement)?.containerId != null }
            val open = layer.id !in collapsed
            out += ElementsRow.LayerHeader(layer, inLayer.size, open)
            if (!open) continue
            for ((_, e) in inLayer.sortedWith(compareByDescending<IndexedValue<SceneElement>> { it.value.base.zKey }.thenByDescending { it.index })) {
                out += ElementsRow.Item(e, layer, ElementNames.nameOf(e), ElementNames.kindWord(e), labelOf(e, labels), e.base.locked || layer.locked, !layer.visible)
            }
        }
        return out
    }

    /** What activating [item] does (UX-12): a hidden layer selects nothing, a locked element is selected with Unlock on offer. */
    fun activate(item: ElementsRow.Item): RowOutcome =
        if (item.layerHidden) RowOutcome.LayerHidden(SetLayerVisible(item.layer.id, true))
        else RowOutcome.Select(item.element.id, if (item.element.base.locked) LockElements(listOf(item.element.id), false) else null)

    /** The view after "select and pan": the element's extent brought inside [visible] without zooming; null for no extent. */
    fun reveal(scene: SketchSceneV2, id: ElementId, viewport: SketchViewport, visible: ScreenRect): RevealResult? =
        scene[id]?.let(SceneBounds::of)?.let { SelectionReveal.reveal(viewport, it, visible) }

    /** The command that renames [id] to [text] (blank resets to "Kind n"). */
    fun rename(id: ElementId, text: String): Command = RenameElement(id, text)

    /** The words for a refused rename; null for an error the Elements tab has no special text for. */
    fun renameError(error: CommandError): String? = when (error) {
        is CommandError.NameTaken -> NAME_TAKEN
        is CommandError.NameTooLong -> "Names can have up to ${error.limit} characters"
        else -> null
    }

    /** The rows a list shows for a window of [count] rows from [first]: only these are built (virtualised, Story 3.5.2). */
    fun window(rows: List<ElementsRow>, first: Int, count: Int): List<ElementsRow> =
        rows.subList(first.coerceIn(0, rows.size), (first + count).coerceIn(0, rows.size))

    private fun boundLabels(scene: SketchSceneV2): Map<ElementId, String> {
        val out = HashMap<ElementId, String>()
        for (e in scene.elementsInOrder) {
            val container = (e as? TextElement)?.containerId ?: continue
            out[container] = e.text
        }
        return out
    }

    private fun labelOf(e: SceneElement, labels: Map<ElementId, String>): String {
        val raw = if (e is TextElement && e.containerId == null) e.text else labels[e.id].orEmpty()
        val oneLine = raw.replace('\n', ' ').trim()
        return if (oneLine.length > LABEL_PREVIEW_CHARS) oneLine.take(LABEL_PREVIEW_CHARS) + "…" else oneLine
    }
}
