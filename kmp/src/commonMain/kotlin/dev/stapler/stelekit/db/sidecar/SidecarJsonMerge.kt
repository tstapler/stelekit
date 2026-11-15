// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db.sidecar

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Carries keys this build does not know (written by a newer build) across a re-save, at the top level,
 * on `imageAnnotation` and on each measurement (matched by `uuid`). Known keys always come from the new text,
 * so a cleared field is never resurrected; text with nothing to carry is returned untouched.
 */
internal object SidecarJsonMerge {
    private val fileKeys = SidecarFile.serializer().descriptor.keys()
    private val annotationKeys = SidecarImageAnnotation.serializer().descriptor.keys()
    private val measurementKeys = SidecarMeasurement.serializer().descriptor.keys()

    private fun SerialDescriptor.keys(): Set<String> = (0 until elementsCount).map(::getElementName).toSet()

    fun overlayUnknownKeys(existingText: String?, newText: String, json: Json): String {
        if (existingText.isNullOrBlank()) return newText
        val existing = parseObject(existingText, json) ?: return newText
        val fresh = parseObject(newText, json) ?: return newText
        val merged = overlay(existing, fresh, fileKeys, mapOf(
            "imageAnnotation" to { old, new -> overlay(old.asObject(), new.asObject(), annotationKeys) },
            "measurements" to { old, new -> overlayMeasurements(old, new) },
        ))
        return if (merged == fresh) newText else json.encodeToString(JsonObject.serializer(), merged)
    }

    private fun parseObject(text: String, json: Json): JsonObject? =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun JsonElement?.asObject(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

    private fun overlay(
        old: JsonObject,
        new: JsonObject,
        known: Set<String>,
        nested: Map<String, (JsonElement?, JsonElement) -> JsonElement> = emptyMap(),
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in new) out[key] = nested[key]?.invoke(old[key], value) ?: value
        for ((key, value) in old) if (key !in known && key !in out) out[key] = value
        return JsonObject(out)
    }

    private fun overlayMeasurements(old: JsonElement?, new: JsonElement): JsonElement {
        val oldByUuid = (old as? JsonArray).orEmpty().mapNotNull { e ->
            (e as? JsonObject)?.let { o -> (o["uuid"] as? JsonPrimitive)?.content?.let { it to o } }
        }.toMap()
        return JsonArray(
            (new as? JsonArray).orEmpty().map { e ->
                val obj = e as? JsonObject ?: return@map e
                val previous = (obj["uuid"] as? JsonPrimitive)?.content?.let(oldByUuid::get)
                if (previous == null) obj else overlay(previous, obj, measurementKeys)
            },
        )
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
