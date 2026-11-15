// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db.sidecar

import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.GeometryCalibrationCodec
import dev.stapler.stelekit.model.LensCalibration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Frozen copies of the v0.113.3 data classes (as shipped before multi-distance calibration). Do not edit. */
@Serializable
private data class OldSidecarFile(
    @SerialName("schemaVersion") val schemaVersion: Int = 1,
    @SerialName("imageAnnotation") val imageAnnotation: OldSidecarImageAnnotation,
    @SerialName("measurements") val measurements: List<OldSidecarMeasurement> = emptyList(),
)

@Serializable
private data class OldSidecarImageAnnotation(
    @SerialName("uuid") val uuid: String,
    @SerialName("blockUuid") val blockUuid: String,
    @SerialName("pageUuid") val pageUuid: String,
    @SerialName("graphPath") val graphPath: String,
    @SerialName("filePath") val filePath: String,
    @SerialName("importedAtMs") val importedAtMs: Long = 0L,
    @SerialName("calibrationMethod") val calibrationMethod: String = "NONE",
    @SerialName("pixelsPerMeter") val pixelsPerMeter: Double = 0.0,
    @SerialName("calibrationConfidencePct") val calibrationConfidencePct: Int = 0,
    @SerialName("calibrationBasisVersion") val calibrationBasisVersion: Int = 0,
    @SerialName("calibrationLens") val calibrationLens: LensCalibration? = null,
    @SerialName("calibrationGeometry") val calibrationGeometry: OldGeometryCalibration? = null,
    @SerialName("unit") val unit: String = "METERS",
)

@Serializable
private data class OldSidecarMeasurement(
    @SerialName("uuid") val uuid: String,
    @SerialName("imageUuid") val imageUuid: String,
    @SerialName("annotationType") val annotationType: String,
    @SerialName("normalizedPoints") val normalizedPoints: List<SidecarPoint> = emptyList(),
    @SerialName("valueMeters") val valueMeters: Double? = null,
    @SerialName("valueDisplay") val valueDisplay: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("colorHex") val colorHex: String = "#FF0000",
    @SerialName("bleDeviceId") val bleDeviceId: String? = null,
)

@Serializable
private data class OldGeometryCalibration(
    val heightOffsetMeters: Double = 0.0,
    val cameraDistanceMeters: Double? = null,
    val plane: OldPlaneCalibration? = null,
)

@Serializable
private data class OldPlaneCalibration(
    val homography: List<Double>,
    val referenceName: String,
    val widthMeters: Double,
    val heightMeters: Double,
    val checkResidualMm: Double? = null,
)

/** Task 3.2c2 (I-E3-02): a v0.113.3 reader reads new data as calibrated and only loses the new keys on re-save. */
class FrozenOldReaderDecodeTest {
    private val oldJson = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** The old `usableScale` rule: a method other than NONE and a positive finite scale. */
    private fun oldUsable(a: OldSidecarImageAnnotation): Boolean {
        val method = runCatching { CalibrationMethod.valueOf(a.calibrationMethod) }.getOrDefault(CalibrationMethod.NONE)
        return method != CalibrationMethod.NONE && a.pixelsPerMeter.isFinite() && a.pixelsPerMeter > 0.0
    }

    private fun decode(text: String) = oldJson.decodeFromString(OldSidecarFile.serializer(), text)

    private val droppedKeys = listOf("constraints", "\"fit\"", "\"kind\"", "valueSource", "originalValueMeters")

    @Test
    fun frozen0113Decoder_should_ReadNewSidecarAndDbJsonAsCalibrated_WithOverridesLostOnlyOnResave() {
        val file = decode(SidecarFixtures.V2)
        val annotation = file.imageAnnotation
        assertTrue(oldUsable(annotation))
        assertEquals(1843.7512345, annotation.pixelsPerMeter)
        val plane = assertNotNull(annotation.calibrationGeometry?.plane)
        assertEquals(9, plane.homography.size)
        assertTrue(plane.widthMeters > 0.0 && plane.heightMeters > 0.0)
        assertNull(plane.checkResidualMm)
        assertEquals(0.9, file.measurements.single().valueMeters)

        val resaved = oldJson.encodeToString(OldSidecarFile.serializer(), file)
        droppedKeys.forEach { assertFalse(it in resaved, "$it survived an old-reader re-save: $resaved") }
        assertTrue("\"pixelsPerMeter\":1843.7512345" in resaved && "\"homography\"" in resaved)
    }

    @Test
    fun scaleTierFit_should_NotBeReadAsUncalibrated_ByTheOldLoader() {
        val file = decode(SidecarFixtures.V2_SCALE)
        assertTrue(oldUsable(file.imageAnnotation))
        assertNull(file.imageAnnotation.calibrationGeometry?.plane)
        assertEquals(CalibrationMethod.MANUAL_REFERENCE, CalibrationMethod.valueOf(file.imageAnnotation.calibrationMethod))
        assertFalse(CalibrationMethod.entries.any { it.name in setOf("CONSTRAINT_FIT", "KNOWN_DISTANCES") })
    }

    @Test
    fun geometryColumnJson_should_DecodeInBothTheCurrentAndTheFrozenCodec() {
        val raw = Json.parseToJsonElement(SidecarFixtures.V2).jsonObject["imageAnnotation"]!!.jsonObject["calibrationGeometry"]!!
        val text = (raw as JsonObject).toString()
        val current = assertNotNull(GeometryCalibrationCodec.decode(text))
        assertEquals(1, current.constraints.size)
        assertNotNull(current.fit)
        val old = oldJson.decodeFromString(OldGeometryCalibration.serializer(), text)
        assertEquals(0.002, old.heightOffsetMeters)
        assertNotNull(old.plane)
    }
}
