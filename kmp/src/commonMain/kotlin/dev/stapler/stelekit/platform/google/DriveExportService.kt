// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val sidecarJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

/**
 * Result of a successful Drive export containing both file IDs.
 */
data class DriveExportResult(
    /** Drive file ID of the annotated JPEG. */
    val imageFileId: String,
    /** Drive file ID of the .measure.json sidecar. */
    val sidecarFileId: String,
    /** Direct Drive view link for the image (share-friendly). */
    val imageLink: String = "https://drive.google.com/file/d/$imageFileId/view",
)

/**
 * Sidecar JSON structure for the .measure.json file exported to Drive.
 * Version-stamped for future schema evolution.
 */
@Serializable
private data class MeasureSidecar(
    val version: Int = 1,
    @SerialName("image_annotation") val imageAnnotation: ImageAnnotationSidecar,
    val measurements: List<MeasurementSidecar>,
)

@Serializable
private data class ImageAnnotationSidecar(
    val uuid: String,
    @SerialName("block_uuid") val blockUuid: String,
    @SerialName("page_uuid") val pageUuid: String,
    @SerialName("file_path") val filePath: String,
    val source: String,
    @SerialName("source_uri") val sourceUri: String?,
    @SerialName("calibration_method") val calibrationMethod: String,
    @SerialName("calibration_pixels_per_meter") val calibrationPixelsPerMeter: Double,
    @SerialName("calibration_confidence") val calibrationConfidence: Int,
    val unit: String,
    val tags: List<String>,
)

@Serializable
private data class MeasurementSidecar(
    val uuid: String,
    @SerialName("annotation_type") val annotationType: String,
    @SerialName("normalized_points") val normalizedPoints: List<PointSidecar>,
    @SerialName("value_meters") val valueMeters: Double?,
    @SerialName("value_display") val valueDisplay: String?,
    val label: String?,
    @SerialName("ble_device_id") val bleDeviceId: String?,
)

@Serializable
private data class PointSidecar(val x: Double, val y: Double)

/**
 * Minimal interface for Drive file operations needed by [DriveExportService].
 *
 * Extracted from [GoogleApiClient] to allow test doubles without subclassing.
 */
interface DriveUploader {
    suspend fun uploadFile(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        parentFolderId: String?,
    ): Either<DomainError, String>
}

/**
 * Service responsible for exporting annotated images and sidecar JSON to Google Drive.
 *
 * Flow:
 * 1. Receive [imageJpegBytes] from [AnnotationExporter.bakeAndEncode].
 * 2. Serialize [imageAnnotation] + [measurements] to a .measure.json sidecar.
 * 3. Upload annotated JPEG to Drive.
 * 4. Upload JSON sidecar to Drive in the same folder.
 * 5. Return [DriveExportResult] with both file IDs.
 *
 * For files > 5 MB: callers should show progress UI. The upload itself is a single
 * blocking suspend call; WorkManager integration for large files is handled in the ViewModel.
 */
open class DriveExportService(private val apiClient: DriveUploader) {

    /**
     * Export the annotated image and sidecar JSON to Google Drive.
     *
     * @param imageAnnotation The [ImageAnnotation] metadata.
     * @param measurements The list of committed [MeasurementAnnotation]s baked into the image.
     * @param imageJpegBytes The JPEG-encoded annotated image from [AnnotationExporter.bakeAndEncode].
     * @param folderId Optional Drive folder ID. Uploads to Drive root if null.
     */
    suspend fun exportToDrive(
        imageAnnotation: ImageAnnotation,
        measurements: List<MeasurementAnnotation>,
        imageJpegBytes: ByteArray,
        folderId: String? = null,
    ): Either<DomainError, DriveExportResult> {
        // Derive file names from the image UUID + annotation count
        val baseName = "${imageAnnotation.uuid}_annotated"
        val imageName = "$baseName.jpg"
        val sidecarName = "$baseName.measure.json"

        // Upload the annotated JPEG
        val imageFileId = apiClient.uploadFile(
            fileName = imageName,
            mimeType = "image/jpeg",
            bytes = imageJpegBytes,
            parentFolderId = folderId,
        ).fold(
            ifLeft = { err ->
                return DomainError.NetworkError.HttpError(
                    statusCode = if (err is DomainError.NetworkError.HttpError) err.statusCode else -1,
                    message = "Drive upload of annotated JPEG failed: ${err.message}",
                ).left()
            },
            ifRight = { it },
        )

        // Serialize and upload the sidecar JSON
        val sidecarBytes = buildSidecarJson(imageAnnotation, measurements).encodeToByteArray()
        val sidecarFileId = apiClient.uploadFile(
            fileName = sidecarName,
            mimeType = "application/json",
            bytes = sidecarBytes,
            parentFolderId = folderId,
        ).fold(
            ifLeft = { err ->
                return DomainError.NetworkError.HttpError(
                    statusCode = if (err is DomainError.NetworkError.HttpError) err.statusCode else -1,
                    message = "Drive upload of measurement sidecar JSON failed: ${err.message}",
                ).left()
            },
            ifRight = { it },
        )

        return DriveExportResult(
            imageFileId = imageFileId,
            sidecarFileId = sidecarFileId,
        ).right()
    }

    private fun buildSidecarJson(
        imageAnnotation: ImageAnnotation,
        measurements: List<MeasurementAnnotation>,
    ): String {
        val sidecar = MeasureSidecar(
            imageAnnotation = ImageAnnotationSidecar(
                uuid = imageAnnotation.uuid,
                blockUuid = imageAnnotation.blockUuid,
                pageUuid = imageAnnotation.pageUuid,
                filePath = imageAnnotation.filePath,
                source = imageAnnotation.source.name,
                sourceUri = imageAnnotation.sourceUri,
                calibrationMethod = imageAnnotation.calibration.method.name,
                calibrationPixelsPerMeter = imageAnnotation.calibration.pixelsPerMeter,
                calibrationConfidence = imageAnnotation.calibration.confidencePercent,
                unit = imageAnnotation.unit.name,
                tags = imageAnnotation.tags,
            ),
            measurements = measurements.map { m ->
                MeasurementSidecar(
                    uuid = m.uuid,
                    annotationType = m.annotationType.name,
                    normalizedPoints = m.normalizedPoints.map { PointSidecar(it.x, it.y) },
                    valueMeters = m.valueMeters,
                    valueDisplay = m.valueDisplay,
                    label = m.label,
                    bleDeviceId = m.bleDeviceId,
                )
            },
        )
        return sidecarJson.encodeToString(sidecar)
    }
}
