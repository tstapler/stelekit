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

/** A bound wiki page, as stored in the sidecar `sources[]`: ids and a hash only, never a title or path (OD-H8). */
data class SourceBinding(val kind: SourceBindingKind, val pageUuid: String, val contentHash: String)

/** Reads and rewrites the page bindings inside a project's `settings["sources"]`, leaving yaml/svg entries alone. */
object SourceBindings {
    const val KEY = "sources"
    const val MAX_BINDINGS = 50

    fun of(settings: JsonObject): List<SourceBinding> = entries(settings).mapNotNull(::decode)

    /** Adds or replaces the binding for the same kind and page; null once [MAX_BINDINGS] would be exceeded. */
    fun with(settings: JsonObject, binding: SourceBinding): JsonObject? {
        val others = entries(settings).filterNot { sameTarget(it, binding.kind, binding.pageUuid) }
        val pageCount = others.count { decode(it) != null }
        if (pageCount + 1 > MAX_BINDINGS) return null
        return replaceEntries(settings, others + encode(binding))
    }

    fun without(settings: JsonObject, kind: SourceBindingKind, pageUuid: String): JsonObject =
        replaceEntries(settings, entries(settings).filterNot { sameTarget(it, kind, pageUuid) })

    fun encode(b: SourceBinding): JsonObject =
        JsonObject(mapOf("kind" to JsonPrimitive(b.kind.wire), "pageUuid" to JsonPrimitive(b.pageUuid), "contentHash" to JsonPrimitive(b.contentHash)))

    private fun entries(settings: JsonObject): List<JsonElement> = (settings[KEY] as? JsonArray)?.toList().orEmpty()

    private fun replaceEntries(settings: JsonObject, list: List<JsonElement>): JsonObject {
        val sorted = list.sortedWith(compareBy({ decode(it)?.kind?.wire ?: "" }, { decode(it)?.pageUuid ?: "" }))
        val rest = settings - KEY
        return if (sorted.isEmpty()) JsonObject(rest) else JsonObject(rest + (KEY to JsonArray(sorted)))
    }

    private fun sameTarget(e: JsonElement, kind: SourceBindingKind, pageUuid: String) =
        decode(e).let { it != null && it.kind == kind && it.pageUuid == pageUuid }

    private fun decode(e: JsonElement): SourceBinding? {
        val o = (e as? JsonObject) ?: return null
        val kind = SourceBindingKind.fromWire(o["kind"]?.jsonPrimitive?.contentOrNull) ?: return null
        val uuid = o["pageUuid"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val hash = o["contentHash"]?.jsonPrimitive?.contentOrNull ?: return null
        return SourceBinding(kind, uuid, hash)
    }
}
