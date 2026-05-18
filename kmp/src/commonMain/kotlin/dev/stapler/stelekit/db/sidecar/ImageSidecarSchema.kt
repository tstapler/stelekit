package dev.stapler.stelekit.db.sidecar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * JSON sidecar schema v1 for image annotations.
 *
 * The sidecar file at `<graph>/.stelekit/images/<uuid>.measure.json` is the
 * authoritative portable source of truth for measurement data. The SQLDelight row is
 * a query-optimised cache that can always be rebuilt from the sidecar via
 * [ImageSidecarIndexer.rebuildFromSidecars].
 *
 * IMPORTANT: write the sidecar BEFORE committing the SQLDelight row so that a crash
 * between the two writes never leaves a DB row without a corresponding sidecar file.
 */

@Serializable
data class SidecarFile(
    @SerialName("schemaVersion") val schemaVersion: Int = 1,
    @SerialName("imageAnnotation") val imageAnnotation: SidecarImageAnnotation,
    @SerialName("measurements") val measurements: List<SidecarMeasurement> = emptyList(),
)

@Serializable
data class SidecarImageAnnotation(
    @SerialName("uuid") val uuid: String,
    @SerialName("blockUuid") val blockUuid: String,
    @SerialName("pageUuid") val pageUuid: String,
    @SerialName("graphPath") val graphPath: String,
    @SerialName("filePath") val filePath: String,
    @SerialName("thumbnailPath") val thumbnailPath: String? = null,
    @SerialName("source") val source: String = "FILE",
    @SerialName("sourceUri") val sourceUri: String? = null,
    @SerialName("capturedAtMs") val capturedAtMs: Long? = null,
    @SerialName("importedAtMs") val importedAtMs: Long = 0L,
    @SerialName("calibrationMethod") val calibrationMethod: String = "NONE",
    @SerialName("pixelsPerMeter") val pixelsPerMeter: Double = 0.0,
    @SerialName("calibrationConfidencePct") val calibrationConfidencePct: Int = 0,
    @SerialName("unit") val unit: String = "METERS",
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("latLng") val latLng: String? = null,
    @SerialName("altitudeM") val altitudeM: Double? = null,
    @SerialName("bearingDeg") val bearingDeg: Double? = null,
    @SerialName("pitchDeg") val pitchDeg: Double? = null,
    @SerialName("rollDeg") val rollDeg: Double? = null,
    @SerialName("focalLengthMm") val focalLengthMm: Double? = null,
    @SerialName("focalLength35mmEq") val focalLength35mmEq: Double? = null,
    @SerialName("cameraMake") val cameraMake: String? = null,
    @SerialName("cameraModel") val cameraModel: String? = null,
)

@Serializable
data class SidecarMeasurement(
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
data class SidecarPoint(
    @SerialName("x") val x: Double,
    @SerialName("y") val y: Double,
)
