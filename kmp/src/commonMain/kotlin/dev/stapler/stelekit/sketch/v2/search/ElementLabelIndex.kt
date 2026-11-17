// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.search

import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement

/** One searchable element: [name] is "Kind n" or the user's name, [text] the text of a text element or of the label bound to a shape. */
data class LabelEntry(
    val id: ElementId,
    val name: String,
    val kind: String,
    val text: String,
    val locked: Boolean,
    val layerHidden: Boolean,
) {
    internal val nameKey: String = normalise(name)
    internal val textKey: String = normalise(text)
    internal val haystack: String = "$nameKey $textKey"
}

/** The shown matches (at most `SceneLimits.maxGoToResults`) and how many more matched, for "N more" (UX U5). */
data class LabelMatches(val shown: List<LabelEntry>, val more: Int)

/**
 * The search index of "Go to element" (Story 3.6.5, Task 3.6.5a). Pure: it indexes both element names and text (a label
 * bound to a container is indexed under the container, so searching "billing" finds the rectangle that says it), in paint
 * order, and answers a query with at most the scene's `maxGoToResults` entries. It builds one small record per element
 * and never a UI node, so a 3,000-element sheet is searched without composing anything per element.
 */
class ElementLabelIndex private constructor(private val entries: List<LabelEntry>, private val limit: Int) {
    val size: Int get() = entries.size

    /**
     * Every word of [query] must occur in the name or text, in any order and any case. Entries whose name or text starts with
     * the query come first, then those with a word starting with it, then the rest; paint order breaks ties. A blank query
     * lists the first entries in paint order.
     */
    fun search(query: String): LabelMatches {
        val words = normalise(query).split(' ').filter { it.isNotEmpty() }
        val whole = words.joinToString(" ")
        val matching = entries.withIndex().filter { (_, e) -> words.all { it in e.haystack } }
        val ranked = if (words.isEmpty()) matching else matching.sortedWith(compareBy({ rank(it.value, whole) }, { it.index }))
        return LabelMatches(ranked.take(limit).map { it.value }, (ranked.size - limit).coerceAtLeast(0))
    }

    private fun rank(e: LabelEntry, whole: String): Int = when {
        e.nameKey.startsWith(whole) || e.textKey.startsWith(whole) -> RANK_PREFIX
        " $whole" in " ${e.haystack}" -> RANK_WORD
        else -> RANK_ANYWHERE
    }

    companion object {
        private const val RANK_PREFIX = 0
        private const val RANK_WORD = 1
        private const val RANK_ANYWHERE = 2

        fun of(scene: SketchSceneV2): ElementLabelIndex {
            val labels = HashMap<ElementId, String>()
            for (e in scene.elementsInOrder) (e as? TextElement)?.containerId?.let { labels[it] = e.text }
            val entries = scene.elementsInOrder.filter { !isBoundLabel(it) }.map { e ->
                val layer = scene.layer(e.base.layerId) ?: Layer.DefaultLayer
                LabelEntry(e.id, ElementNames.nameOf(e), ElementNames.kindWord(e), textOf(e, labels, scene.limits.maxNameChars), e.base.locked || layer.locked, !layer.visible)
            }
            return ElementLabelIndex(entries, scene.limits.maxGoToResults)
        }

        /** A shape's own label is part of the shape, so it is not a second result. */
        private fun isBoundLabel(e: SceneElement) = e is TextElement && e.containerId != null

        private fun textOf(e: SceneElement, labels: Map<ElementId, String>, maxChars: Int): String {
            val raw = if (e is TextElement) e.text else labels[e.id].orEmpty()
            return raw.replace('\n', ' ').trim().take(maxChars)
        }
    }
}

private fun normalise(text: String): String = text.lowercase().replace('\n', ' ').trim().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
