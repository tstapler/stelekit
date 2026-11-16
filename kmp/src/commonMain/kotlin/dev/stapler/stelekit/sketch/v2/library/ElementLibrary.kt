// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.library

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.PasteElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One saved selection. [bytes] is its stored size, the quantity `SceneLimits.maxLibraryBytes` caps. */
data class LibraryItem(val id: String, val name: String?, val created: Long, val elements: List<SceneElement>, val bytes: Long)

/** Why an item was not stored; [message] is the exact user text (Story 3.5.6). */
sealed interface LibraryError {
    val message: String

    /** The item cap `SceneLimits.maxLibraryItems`. */
    data class TooManyItems(val limit: Int) : LibraryError {
        override val message: String get() = "Library is full ($limit items). Delete an item to add more."
    }

    /** The stored-bytes cap `SceneLimits.maxLibraryBytes`. */
    data class TooManyBytes(val limitMb: Long) : LibraryError {
        override val message: String get() = "Library is full ($limitMb MB). Delete an item to add more."
    }

    data object NothingSelected : LibraryError {
        override val message: String get() = "Select something to add to the library."
    }

    data object Unreadable : LibraryError {
        override val message: String get() = "That item cannot be stored."
    }
}

/** Where the library text is kept (per graph; a Settings or asset-backed adapter implements it outside the engine). */
interface LibraryStorage {
    fun read(): String?

    fun write(text: String)
}

class InMemoryLibraryStorage(private var text: String? = null) : LibraryStorage {
    override fun read(): String? = text

    override fun write(text: String) {
        this.text = text
    }
}

/** What an import did: the items stored and how many did not fit (reported as `LibraryFull`, never half-written). */
data class LibraryMergeResult(val library: ElementLibrary, val added: Int, val skippedFull: Int)

/**
 * The element library (Story 3.5.6a): saved selections that insert as fresh copies. Immutable and bounded by
 * `SceneLimits.maxLibraryItems` and `maxLibraryBytes`; a refused add changes nothing. Each item is stored through the
 * v2 sheet encoding so every element field survives; `.excalidrawlib` exchange is `LibraryCodec`.
 */
class ElementLibrary private constructor(val items: List<LibraryItem>, private val limits: SceneLimits) {
    val totalBytes: Long get() = items.sumOf { it.bytes }

    /** Saves [elements] as a new item; refused with the exact message when either cap would be passed. */
    fun add(id: String, name: String?, elements: List<SceneElement>, created: Long): Either<LibraryError, ElementLibrary> {
        if (elements.isEmpty()) return LibraryError.NothingSelected.left()
        val item = item(id, name, created, elements) ?: return LibraryError.Unreadable.left()
        return admit(item)
    }

    private fun admit(item: LibraryItem): Either<LibraryError, ElementLibrary> = when {
        items.size >= limits.maxLibraryItems -> LibraryError.TooManyItems(limits.maxLibraryItems).left()
        totalBytes + item.bytes > limits.maxLibraryBytes -> LibraryError.TooManyBytes(limits.maxLibraryBytes / BYTES_PER_MB).left()
        else -> ElementLibrary(items + item, limits).right()
    }

    fun delete(id: String): ElementLibrary = ElementLibrary(items.filterNot { it.id == id }, limits)

    /**
     * Adds imported items in file order while they fit; the rest are counted as skipped. An item is never stored
     * partially, so the library is always loadable.
     */
    fun merge(imported: List<LibraryItem>): LibraryMergeResult {
        var out = this
        var added = 0
        var skipped = 0
        for (item in imported) {
            val next = out.admit(item.copy(bytes = measure(item.elements) ?: item.bytes))
            if (next.isRight()) {
                out = next.getOrNull()!!
                added++
            } else {
                skipped++
            }
        }
        return LibraryMergeResult(out, added, skipped)
    }

    /** The command that inserts item [itemId] with fresh ids at an offset; inserting twice gives two disjoint copies. */
    fun insert(itemId: String, ids: ElementIdSource, dx: Double = 0.0, dy: Double = 0.0): Command? {
        val item = items.firstOrNull { it.id == itemId } ?: return null
        return PasteElements(item.elements, item.elements.associate { it.id to ids.next() }, dx, dy)
    }

    /** The text a [LibraryStorage] keeps. */
    fun serialize(): String {
        val array = JsonArray(
            items.map { item ->
                buildJsonObject {
                    put("id", item.id)
                    item.name?.let { put("name", it) }
                    put("created", item.created)
                    put("sheet", sheetText(item.elements) ?: "")
                }
            },
        )
        return JsonObject(mapOf("items" to array)).toString()
    }

    private fun item(id: String, name: String?, created: Long, elements: List<SceneElement>): LibraryItem? =
        measure(elements)?.let { LibraryItem(id, name, created, elements, it) }

    private fun sheetText(elements: List<SceneElement>): String? {
        var scene = SketchSceneV2.empty(limits)
        for (e in elements) scene = scene.add(e).getOrNull() ?: return null
        return SheetCodecV2.encode(SketchDocumentV2(scene = scene), limits).getOrNull()?.decodeToString()
    }

    private fun measure(elements: List<SceneElement>): Long? = sheetText(elements)?.encodeToByteArray()?.size?.toLong()

    companion object {
        private const val BYTES_PER_MB = 1_048_576L

        fun empty(limits: SceneLimits = SceneLimits.Default) = ElementLibrary(emptyList(), limits)

        /** Reads what [serialize] wrote; an unreadable or absent text is an empty library, never a crash. */
        fun load(storage: LibraryStorage, limits: SceneLimits = SceneLimits.Default): ElementLibrary {
            val text = storage.read() ?: return empty(limits)
            val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return empty(limits)
            val stored = (root["items"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val sheet = (o["sheet"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val doc = SheetCodecV2.decode(sheet.encodeToByteArray(), limits).getOrNull() ?: return@mapNotNull null
                LibraryItem(id, (o["name"] as? JsonPrimitive)?.contentOrNull, o["created"]?.jsonPrimitive?.longOrNull ?: 0L, doc.scene.elementsInOrder, sheet.encodeToByteArray().size.toLong())
            }
            return ElementLibrary(stored.take(limits.maxLibraryItems), limits)
        }
    }

    fun save(storage: LibraryStorage) = storage.write(serialize())
}
