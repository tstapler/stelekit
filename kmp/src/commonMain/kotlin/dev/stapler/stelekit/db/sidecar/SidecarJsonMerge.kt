// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db.sidecar

import dev.stapler.stelekit.model.CalibrationConstraint
import dev.stapler.stelekit.model.ConstraintFitRecord
import dev.stapler.stelekit.model.GeometryCalibration
import dev.stapler.stelekit.model.PlaneCalibration
import dev.stapler.stelekit.model.PriorBasis
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Carries keys this build does not know (written by a newer build) across a re-save, at the top level,
 * on `imageAnnotation`, its calibration geometry (plane, constraints by `id`, fit, prior basis) and on each
 * measurement (matched by `uuid`); a file at a higher `schemaVersion` keeps it. Known keys always come from the new text,
 * so a cleared field is never resurrected; text with nothing to carry is returned untouched.
 */
internal object SidecarJsonMerge {
    private val fileKeys = SidecarFile.serializer().descriptor.keys()
    private val annotationKeys = SidecarImageAnnotation.serializer().descriptor.keys()
    private val measurementKeys = SidecarMeasurement.serializer().descriptor.keys()
    private val geometryKeys = GeometryCalibration.serializer().descriptor.keys()
    private val planeKeys = PlaneCalibration.serializer().descriptor.keys()
    private val constraintKeys = CalibrationConstraint.serializer().descriptor.keys()
    private val fitKeys = ConstraintFitRecord.serializer().descriptor.keys()
    private val priorKeys = PriorBasis.serializer().descriptor.keys()

    private fun SerialDescriptor.keys(): Set<String> = (0 until elementsCount).map(::getElementName).toSet()

    fun overlayUnknownKeys(existingText: String?, newText: String, json: Json): String {
        if (existingText.isNullOrBlank()) return newText
        val existing = parseObject(existingText, json) ?: return newText
        val fresh = parseObject(newText, json) ?: return newText
        val plane = objectOverlay(planeKeys)
        val prior = objectOverlay(priorKeys, mapOf("plane" to plane))
        val geometry = objectOverlay(
            geometryKeys,
            mapOf(
                "plane" to plane,
                "fit" to objectOverlay(fitKeys, mapOf("priorBasis" to prior)),
                "constraints" to { old, new -> overlayByKey(old, new, "id", constraintKeys) },
            ),
        )
        val merged = overlay(existing, fresh, fileKeys, mapOf(
            "schemaVersion" to { old, new -> newerVersion(old, new) },
            "imageAnnotation" to objectOverlay(annotationKeys, mapOf("calibrationGeometry" to geometry)),
            "measurements" to { old, new -> overlayByKey(old, new, "uuid", measurementKeys) },
        ))
        return if (merged == fresh) newText else json.encodeToString(JsonObject.serializer(), merged)
    }

    private fun parseObject(text: String, json: Json): JsonObject? =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    /** Overlays an object onto the old one at the same key; a new value that is not an object (null) is kept as is. */
    private fun objectOverlay(
        known: Set<String>,
        nested: Map<String, (JsonElement?, JsonElement) -> JsonElement> = emptyMap(),
    ): (JsonElement?, JsonElement) -> JsonElement = { old, new ->
        if (new is JsonObject) overlay(old as? JsonObject ?: JsonObject(emptyMap()), new, known, nested) else new
    }

    /** A file written by a newer build keeps its higher version, so it is not re-stamped while still holding newer keys. */
    private fun newerVersion(old: JsonElement?, new: JsonElement): JsonElement {
        val before = (old as? JsonPrimitive)?.content?.toIntOrNull() ?: return new
        val after = (new as? JsonPrimitive)?.content?.toIntOrNull() ?: return new
        return if (before > after) old else new
    }

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

    private fun overlayByKey(old: JsonElement?, new: JsonElement, idKey: String, known: Set<String>): JsonElement {
        val oldById = (old as? JsonArray).orEmpty().mapNotNull { e ->
            (e as? JsonObject)?.let { o -> (o[idKey] as? JsonPrimitive)?.content?.let { it to o } }
        }.toMap()
        if (new !is JsonArray) return new
        return JsonArray(
            new.map { e ->
                val obj = e as? JsonObject ?: return@map e
                val previous = (obj[idKey] as? JsonPrimitive)?.content?.let(oldById::get)
                if (previous == null) obj else overlay(previous, obj, known)
            },
        )
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
