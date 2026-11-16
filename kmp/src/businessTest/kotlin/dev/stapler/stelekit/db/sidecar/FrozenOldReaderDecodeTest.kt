// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db.sidecar

import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.GeometryCalibrationCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen key sets of the v0.113.3 data classes (as shipped before multi-distance calibration). Do not edit.
 * An ignoreUnknownKeys decode followed by a re-save keeps exactly these keys, so a JSON projection onto them
 * models the old reader without the serialization compiler plugin, which the Bazel business_tests target lacks.
 */
private object OldReader {
    private val file = setOf("schemaVersion", "imageAnnotation", "measurements")
    private val annotation = setOf(
        "uuid", "blockUuid", "pageUuid", "graphPath", "filePath", "importedAtMs", "calibrationMethod", "pixelsPerMeter",
        "calibrationConfidencePct", "calibrationBasisVersion", "calibrationLens", "calibrationGeometry", "unit",
    )
    private val measurement = setOf(
        "uuid", "imageUuid", "annotationType", "normalizedPoints", "valueMeters", "valueDisplay", "label", "colorHex", "bleDeviceId",
    )
    private val geometry = setOf("heightOffsetMeters", "cameraDistanceMeters", "plane")
    private val plane = setOf("homography", "referenceName", "widthMeters", "heightMeters", "checkResidualMm")

    private fun JsonObject.keep(keys: Set<String>) = JsonObject(filterKeys { it in keys })

    fun geometry(raw: JsonObject): JsonObject {
        val kept = raw.keep(geometry)
        val p = kept["plane"] as? JsonObject ?: return kept
        return JsonObject(kept + ("plane" to p.keep(plane)))
    }

    fun file(raw: JsonObject): JsonObject {
        val kept = raw.keep(file).toMutableMap()
        val ann = (kept.getValue("imageAnnotation") as JsonObject).keep(annotation).toMutableMap()
        (ann["calibrationGeometry"] as? JsonObject)?.let { ann["calibrationGeometry"] = geometry(it) }
        kept["imageAnnotation"] = JsonObject(ann)
        (kept["measurements"] as? JsonArray)?.let { kept["measurements"] = JsonArray(it.map { m -> (m as JsonObject).keep(measurement) }) }
        return JsonObject(kept)
    }
}

/** Task 3.2c2 (I-E3-02): a v0.113.3 reader reads new data as calibrated and only loses the new keys on re-save. */
class FrozenOldReaderDecodeTest {
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.num(key: String) = getValue(key).jsonPrimitive.double

    /** The old `usableScale` rule: a method other than NONE and a positive finite scale. */
    private fun oldUsable(annotation: JsonObject): Boolean {
        val method = runCatching { CalibrationMethod.valueOf(annotation.getValue("calibrationMethod").jsonPrimitive.content) }
            .getOrDefault(CalibrationMethod.NONE)
        val scale = annotation.num("pixelsPerMeter")
        return method != CalibrationMethod.NONE && scale.isFinite() && scale > 0.0
    }

    private fun decode(text: String) = OldReader.file(Json.parseToJsonElement(text).jsonObject)

    private val droppedKeys = listOf("constraints", "\"fit\"", "\"kind\"", "valueSource", "originalValueMeters")

    @Test
    fun frozen0113Decoder_should_ReadNewSidecarAndDbJsonAsCalibrated_WithOverridesLostOnlyOnResave() {
        val file = decode(SidecarFixtures.V2)
        val annotation = file.obj("imageAnnotation")
        assertTrue(oldUsable(annotation))
        assertEquals(1843.7512345, annotation.num("pixelsPerMeter"))
        val plane = annotation.obj("calibrationGeometry").obj("plane")
        assertEquals(9, plane.getValue("homography").jsonArray.size)
        assertTrue(plane.num("widthMeters") > 0.0 && plane.num("heightMeters") > 0.0)
        assertNull(plane["checkResidualMm"])
        assertEquals(0.9, file.getValue("measurements").jsonArray.single().jsonObject.num("valueMeters"))

        val resaved = file.toString()
        droppedKeys.forEach { assertFalse(it in resaved, "$it survived an old-reader re-save: $resaved") }
        assertTrue("\"pixelsPerMeter\":1843.7512345" in resaved && "\"homography\"" in resaved)
    }

    @Test
    fun scaleTierFit_should_NotBeReadAsUncalibrated_ByTheOldLoader() {
        val annotation = decode(SidecarFixtures.V2_SCALE).obj("imageAnnotation")
        assertTrue(oldUsable(annotation))
        assertNull(annotation["calibrationGeometry"]?.jsonObject?.get("plane"))
        assertEquals(CalibrationMethod.MANUAL_REFERENCE, CalibrationMethod.valueOf(annotation.getValue("calibrationMethod").jsonPrimitive.content))
        assertFalse(CalibrationMethod.entries.any { it.name in setOf("CONSTRAINT_FIT", "KNOWN_DISTANCES") })
    }

    @Test
    fun geometryColumnJson_should_DecodeInBothTheCurrentAndTheFrozenCodec() {
        val raw = Json.parseToJsonElement(SidecarFixtures.V2).jsonObject["imageAnnotation"]!!.jsonObject["calibrationGeometry"]!!
        val text = (raw as JsonObject).toString()
        val current = assertNotNull(GeometryCalibrationCodec.decode(text))
        assertEquals(1, current.constraints.size)
        assertNotNull(current.fit)
        val old = OldReader.geometry(raw)
        assertEquals(0.002, old.num("heightOffsetMeters"))
        assertNotNull(old["plane"])
    }
}
