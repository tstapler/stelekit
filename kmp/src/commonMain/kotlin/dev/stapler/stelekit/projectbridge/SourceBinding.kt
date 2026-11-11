// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class SourceBindingKind(val wire: String) {
    TABLE("table"),
    PROSE("prose");

    companion object {
        fun fromWire(s: String?): SourceBindingKind? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * A bound wiki page as stored in the synced sidecar `sources[]`: an opaque [bindingId] and a content hash only. Page uuids are
 * random per device (Task 5.9b-0), so the uuid, title and path stay in the device-local [LocalBindingTable].
 */
data class SourceBinding(val kind: SourceBindingKind, val bindingId: String, val contentHash: String)

/** Opaque binding ids, 8 hex characters, unrelated to any page. */
object BindingIds {
    private val shape = Regex("[0-9a-f]{8}")
    fun isValid(id: String) = shape.matches(id)
    fun generate(random: kotlin.random.Random = kotlin.random.Random.Default): String =
        random.nextInt().toUInt().toString(16).padStart(8, '0')
}

/** Reads and rewrites the page bindings inside a project's `settings["sources"]`, leaving yaml/svg entries alone. */
object SourceBindings {
    const val KEY = "sources"
    const val MAX_BINDINGS = 50

    fun of(settings: JsonObject): List<SourceBinding> = entries(settings).mapNotNull(::decode)

    /** Adds or replaces the binding with the same id; null once [MAX_BINDINGS] would be exceeded. */
    fun with(settings: JsonObject, binding: SourceBinding): JsonObject? {
        val others = entries(settings).filterNot { sameId(it, binding.bindingId) }
        val pageCount = others.count { decode(it) != null }
        if (pageCount + 1 > MAX_BINDINGS) return null
        return replaceEntries(settings, others + encode(binding))
    }

    fun without(settings: JsonObject, bindingId: String): JsonObject =
        replaceEntries(settings, entries(settings).filterNot { sameId(it, bindingId) })

    fun encode(b: SourceBinding): JsonObject =
        JsonObject(mapOf("kind" to JsonPrimitive(b.kind.wire), "bindingId" to JsonPrimitive(b.bindingId), "contentHash" to JsonPrimitive(b.contentHash)))

    private fun entries(settings: JsonObject): List<JsonElement> = (settings[KEY] as? JsonArray)?.toList().orEmpty()

    private fun replaceEntries(settings: JsonObject, list: List<JsonElement>): JsonObject {
        val sorted = list.sortedWith(compareBy({ decode(it)?.kind?.wire ?: "" }, { decode(it)?.bindingId ?: "" }))
        val rest = settings - KEY
        return if (sorted.isEmpty()) JsonObject(rest) else JsonObject(rest + (KEY to JsonArray(sorted)))
    }

    private fun sameId(e: JsonElement, bindingId: String) = decode(e)?.bindingId == bindingId

    private fun decode(e: JsonElement): SourceBinding? {
        val o = (e as? JsonObject) ?: return null
        val kind = SourceBindingKind.fromWire(o["kind"]?.jsonPrimitive?.contentOrNull) ?: return null
        val id = o["bindingId"]?.jsonPrimitive?.contentOrNull?.takeIf(BindingIds::isValid) ?: return null
        val hash = o["contentHash"]?.jsonPrimitive?.contentOrNull ?: return null
        return SourceBinding(kind, id, hash)
    }
}

/** Device-local mapping from binding id to the page uuid on THIS device; never synced. */
object BindingTableCodec {
    fun parse(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
        val i = line.indexOf('=')
        if (i <= 0) null else line.substring(0, i).trim().takeIf(BindingIds::isValid)?.let { it to line.substring(i + 1).trim() }
    }.filter { it.second.isNotEmpty() }.toMap()

    fun format(table: Map<String, String>): String = table.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }
}

class LocalBindingTable(private val settings: dev.stapler.stelekit.platform.Settings) {
    fun pageUuid(slug: String, bindingId: String): String? = load(slug)[bindingId]

    fun put(slug: String, bindingId: String, pageUuid: String) = store(slug, load(slug) + (bindingId to pageUuid))

    fun remove(slug: String, bindingId: String) = store(slug, load(slug) - bindingId)

    private fun load(slug: String) = BindingTableCodec.parse(settings.getString(key(slug), ""))
    private fun store(slug: String, table: Map<String, String>) = settings.putString(key(slug), BindingTableCodec.format(table))
    private fun key(slug: String) = "home_sketch_binding_table_$slug"
}

/** Whether a synced binding points at a page on this device. A [NeedsRebind] binding is shown to the owner, never dropped. */
sealed interface BindingResolution {
    val binding: SourceBinding
    data class Resolved(override val binding: SourceBinding, val pageUuid: String) : BindingResolution
    data class NeedsRebind(override val binding: SourceBinding) : BindingResolution
}

object BindingResolver {
    fun resolve(bindings: List<SourceBinding>, lookup: (bindingId: String) -> String?): List<BindingResolution> =
        bindings.map { b -> lookup(b.bindingId)?.let { BindingResolution.Resolved(b, it) } ?: BindingResolution.NeedsRebind(b) }

    /** Pairs each unresolved binding with a candidate page whose content hash equals the binding's; one page per binding. */
    fun matchByHash(unresolved: List<SourceBinding>, candidateHashes: Map<String, String>): Map<String, String> {
        val used = HashSet<String>()
        val out = LinkedHashMap<String, String>()
        for (b in unresolved) {
            val page = candidateHashes.entries.firstOrNull { it.value == b.contentHash && it.key !in used }?.key ?: continue
            used += page
            out[b.bindingId] = page
        }
        return out
    }
}
