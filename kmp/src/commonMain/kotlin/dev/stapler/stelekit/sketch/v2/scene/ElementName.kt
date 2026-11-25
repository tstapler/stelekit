// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Per-kind monotonic counters of one sheet (Story 3.5.7, D-13). A counter only grows, so a deleted ordinal is never
 * reused and a message that names "Line 3" never points at a different element later. Immutable.
 */
class NameCounters private constructor(private val next: Map<String, Int>) {
    /** The highest ordinal handed out per counter key, for the codec. */
    val issued: Map<String, Int> get() = next

    fun isEmpty(): Boolean = next.isEmpty()

    /** The ordinal [kindKey] hands out next (1 for a kind never seen). */
    fun peek(kindKey: String): Int = (next[kindKey] ?: 0) + 1

    /** Takes the next ordinal of [kindKey]: the ordinal and the counters after it. */
    fun take(kindKey: String): Pair<Int, NameCounters> = peek(kindKey).let { it to NameCounters(next + (kindKey to it)) }

    /** Raises the counter of [kindKey] to at least [ordinal] (an element that arrived with its own ordinal). */
    fun observe(kindKey: String, ordinal: Int): NameCounters =
        if (ordinal <= (next[kindKey] ?: 0)) this else NameCounters(next + (kindKey to ordinal))

    /**
     * Gives every element with `ordinal == 0` the next ordinal of its kind, in the order of [elements] (an imported
     * scene passes them in z order), and returns them with the counters after.
     */
    fun assign(elements: List<SceneElement>): Pair<List<SceneElement>, NameCounters> {
        var counters = this
        for (e in elements) if (e.base.ordinal > 0) counters = counters.observe(ElementNames.counterKey(e), e.base.ordinal)
        val out = elements.map { e ->
            if (e.base.ordinal > 0) {
                e
            } else {
                val (ordinal, after) = counters.take(ElementNames.counterKey(e))
                counters = after
                e.withBase(e.base.copy(ordinal = ordinal))
            }
        }
        return out to counters
    }

    override fun equals(other: Any?): Boolean = other is NameCounters && other.next == next

    override fun hashCode(): Int = next.hashCode()

    companion object {
        val Empty = NameCounters(emptyMap())

        fun of(issued: Map<String, Int>): NameCounters = NameCounters(issued.filterValues { it > 0 })
    }
}

/** The user-visible element name: kind plus per-kind ordinal ("Line 3"), unless the user renamed it. */
object ElementNames {
    fun counterKey(e: SceneElement): String = when (e) {
        is RectElement -> "rectangle"
        is DiamondElement -> "diamond"
        is EllipseElement -> "ellipse"
        is LineElement -> "line"
        is ArrowElement -> "arrow"
        is FreedrawElement -> "freedraw"
        is TextElement -> "text"
        is ImageElement -> "image"
        is FrameElement -> "frame"
        is PointElement -> "point"
        is ArcElement -> "arc"
        is WallElement -> "wall"
        is OpeningElement -> e.kind.name.lowercase()
    }

    /** The kind word: an ellipse with equal radii reads "Circle". */
    fun kindWord(e: SceneElement): String = when (e) {
        is RectElement -> "Rectangle"
        is DiamondElement -> "Diamond"
        is EllipseElement -> if (e.isCircle) "Circle" else "Ellipse"
        is LineElement -> "Line"
        is ArrowElement -> "Arrow"
        is FreedrawElement -> "Drawing"
        is TextElement -> "Text"
        is ImageElement -> "Image"
        is FrameElement -> "Frame"
        is PointElement -> "Point"
        is ArcElement -> "Arc"
        is WallElement -> "Wall"
        is OpeningElement -> e.kind.name
    }

    /** The name shown everywhere: the user's name when set, else "Kind n"; an element with no ordinal reads as its kind. */
    fun nameOf(e: SceneElement): String {
        e.base.name?.takeIf { it.isNotBlank() }?.let { return it }
        return if (e.base.ordinal > 0) "${kindWord(e)} ${e.base.ordinal}" else kindWord(e)
    }

    /** True when another element already carries [name] (case-sensitive), so a rename must be refused. */
    fun isTaken(scene: SketchSceneV2, name: String, except: ElementId? = null): Boolean =
        scene.elementsInOrder.any { it.id != except && nameOf(it) == name }

    /** The `customData` an Excalidraw export carries for [e]: `{"stelekit":{"name": ...}}`. */
    fun excalidrawCustomData(e: SceneElement): JsonObject = buildJsonObject {
        put("stelekit", buildJsonObject { put("name", JsonPrimitive(nameOf(e))) })
    }
}
