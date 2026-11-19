// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.commands.CommandError

/** Named paint-order bucket (ADR-006). `opacity` is 0..1 and multiplies with element opacity. */
data class Layer(
    val id: LayerId,
    val name: String,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacity: Double = 1.0,
    val order: Int = 0,
) {
    init {
        require(opacity.isFinite() && opacity >= 0.0 && opacity <= 1.0) { "layer opacity is 0..1" }
    }

    companion object {
        val DefaultLayer = Layer(LayerId.Default, "Layer 1")
    }
}

/**
 * Immutable v2 scene (ADR-002, Spike 0.1.6): elements in a [PersistentHashTrie] keyed by id, a plain `List` of ids in
 * paint order (array-copied on a reorder), and the layer list. Every mutator returns a new scene that shares
 * structure with this one, so a history of snapshots costs about 0.6 KB each at 5,000 elements. Capacity is
 * `SceneLimits.maxElements`; the default layer always exists.
 *
 * [elementsInOrder] is the paint order; `reconcile` (Story 1.2.3) keeps cross-element invariants.
 */
class SketchSceneV2 private constructor(
    private val trie: PersistentHashTrie<SceneElement>,
    val order: List<ElementId>,
    val layers: List<Layer>,
    val limits: SceneLimits,
) {
    val size: Int get() = order.size

    operator fun get(id: ElementId): SceneElement? = trie.get(id.value)

    operator fun contains(id: ElementId): Boolean = trie.containsKey(id.value)

    /** Elements in paint order. */
    val elementsInOrder: List<SceneElement> get() = order.mapNotNull { trie.get(it.value) }

    fun layer(id: LayerId): Layer? = layers.firstOrNull { it.id == id }

    /** Appends [element] on top of the paint order. */
    fun add(element: SceneElement): Either<CommandError, SketchSceneV2> = when {
        size >= limits.maxElements -> CommandError.CapacityExceeded(limits.maxElements).left()
        contains(element.id) -> CommandError.DuplicateElement(element.id.value).left()
        else -> SketchSceneV2(trie.put(element.id.value, element), order + element.id, layers, limits).right()
    }

    /** Replaces the element that has the same id; paint order is unchanged. */
    fun replace(element: SceneElement): Either<CommandError, SketchSceneV2> =
        if (!contains(element.id)) CommandError.UnknownElement(element.id.value).left()
        else SketchSceneV2(trie.put(element.id.value, element), order, layers, limits).right()

    fun remove(id: ElementId): Either<CommandError, SketchSceneV2> =
        if (!contains(id)) CommandError.UnknownElement(id.value).left()
        else SketchSceneV2(trie.remove(id.value), order.filter { it != id }, layers, limits).right()

    /** Replaces the paint order; [newOrder] must be a permutation of the current ids. */
    fun reorder(newOrder: List<ElementId>): Either<CommandError, SketchSceneV2> {
        val unknown = newOrder.firstOrNull { it !in this }
        return when {
            unknown != null -> CommandError.UnknownElement(unknown.value).left()
            newOrder.size != size || newOrder.toSet().size != size -> CommandError.UnknownElement("order is not a permutation").left()
            else -> SketchSceneV2(trie, newOrder.toList(), layers, limits).right()
        }
    }

    fun withLayers(newLayers: List<Layer>): SketchSceneV2 =
        SketchSceneV2(trie, order, ensureDefaultLayer(newLayers), limits)

    override fun equals(other: Any?): Boolean =
        other is SketchSceneV2 && order == other.order && layers == other.layers && order.all { trie.get(it.value) == other.trie.get(it.value) }

    override fun hashCode(): Int = 31 * order.hashCode() + layers.hashCode()

    companion object {
        fun empty(limits: SceneLimits = SceneLimits.Default): SketchSceneV2 =
            SketchSceneV2(PersistentHashTrie.empty(), emptyList(), listOf(Layer.DefaultLayer), limits)

        private fun ensureDefaultLayer(layers: List<Layer>): List<Layer> =
            if (layers.any { it.id == LayerId.Default }) layers else listOf(Layer.DefaultLayer) + layers
    }
}
