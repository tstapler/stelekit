package dev.stapler.stelekit.db.sidecar

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.ImageSensorData
import dev.stapler.stelekit.model.ImageSource
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Reads and writes `.measure.json` sidecar files for image annotations.
 *
 * Sidecar path convention: `<graphPath>/.stelekit/images/<uuid>.measure.json`
 *
 * IMPORTANT: always call [writeSidecar] BEFORE the SQLDelight row insert.
 * If the sidecar write fails, return the error without touching SQLDelight.
 * This ordering guarantees the sidecar is the authoritative record —
 * [ImageSidecarIndexer.rebuildFromSidecars] can always reconstruct the DB.
 */
class ImageSidecarManager(
    private val fileSystem: FileSystem,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /**
     * Serialize [annotation] + [measurements] to JSON and write to the sidecar path.
     *
     * Returns [Either.Left] if the file system write fails.
     */
    fun writeSidecar(
        annotation: ImageAnnotation,
        measurements: List<MeasurementAnnotation>,
    ): Either<DomainError, Unit> {
        return try {
            val path = sidecarPath(annotation.graphPath, annotation.uuid)
            ensureSidecarDir(annotation.graphPath)

            val sidecar = SidecarFile(
                schemaVersion = 1,
                imageAnnotation = annotation.toSidecar(),
                measurements = measurements.map { it.toSidecar() },
            )
            val jsonString = json.encodeToString(SidecarFile.serializer(), sidecar)
            val written = fileSystem.writeFile(path, jsonString)
            if (written) Unit.right()
            else DomainError.FileSystemError.WriteFailed(path, "FileSystem.writeFile returned false").left()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.FileSystemError.WriteFailed(
                sidecarPath(annotation.graphPath, annotation.uuid),
                e.message ?: "unknown"
            ).left()
        }
    }

    /**
     * Read and deserialize the sidecar for [uuid] in [graphPath].
     *
     * Returns `null` wrapped in [Either.Right] when the file does not exist (not an error).
     * Returns [Either.Left] only for malformed JSON or I/O errors.
     */
    fun readSidecar(graphPath: String, uuid: String): Either<DomainError, SidecarFile?> {
        val path = sidecarPath(graphPath, uuid)
        return try {
            val content = fileSystem.readFile(path) ?: return null.right()
            val sidecar = json.decodeFromString(SidecarFile.serializer(), content)
            sidecar.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: kotlinx.serialization.SerializationException) {
            DomainError.ParseError.InvalidSyntax("Malformed sidecar JSON at $path: ${e.message}").left()
        } catch (e: Exception) {
            DomainError.FileSystemError.ReadFailed(path, e.message ?: "unknown").left()
        }
    }

    /** Compute the sidecar file path for the given graph and annotation UUID. */
    fun sidecarPath(graphPath: String, uuid: String): String =
        "$graphPath/.stelekit/images/$uuid.measure.json"

    private fun ensureSidecarDir(graphPath: String) {
        val steleDir = "$graphPath/.stelekit"
        val imagesDir = "$steleDir/images"
        if (!fileSystem.directoryExists(steleDir)) fileSystem.createDirectory(steleDir)
        if (!fileSystem.directoryExists(imagesDir)) fileSystem.createDirectory(imagesDir)
    }
}

// ── Domain → sidecar conversions ──────────────────────────────────────────────

private fun ImageAnnotation.toSidecar(): SidecarImageAnnotation {
    val latLngStr = sensorData.latLng?.let { (lat, lng) -> "$lat,$lng" }
    return SidecarImageAnnotation(
        uuid = uuid,
        blockUuid = blockUuid,
        pageUuid = pageUuid,
        graphPath = graphPath,
        filePath = filePath,
        thumbnailPath = thumbnailPath,
        source = source.name,
        sourceUri = sourceUri,
        capturedAtMs = capturedAtMs,
        importedAtMs = importedAtMs,
        calibrationMethod = calibration.method.name,
        pixelsPerMeter = calibration.pixelsPerMeter,
        calibrationConfidencePct = calibration.confidencePercent,
        unit = unit.name,
        tags = tags,
        latLng = latLngStr,
        altitudeM = sensorData.altitudeM,
        bearingDeg = sensorData.bearingDeg,
        pitchDeg = sensorData.pitchDeg,
        rollDeg = sensorData.rollDeg,
        focalLengthMm = sensorData.focalLengthMm,
        focalLength35mmEq = sensorData.focalLength35mmEq,
        cameraMake = sensorData.cameraMake,
        cameraModel = sensorData.cameraModel,
    )
}

private fun MeasurementAnnotation.toSidecar(): SidecarMeasurement =
    SidecarMeasurement(
        uuid = uuid,
        imageUuid = imageUuid,
        annotationType = annotationType.name,
        normalizedPoints = normalizedPoints.map { SidecarPoint(it.x, it.y) },
        valueMeters = valueMeters,
        valueDisplay = valueDisplay,
        label = label,
        colorHex = colorHex,
        bleDeviceId = bleDeviceId,
    )

// ── Sidecar → domain conversions ──────────────────────────────────────────────

fun SidecarFile.toDomainAnnotation(): ImageAnnotation {
    val ann = imageAnnotation
    val latLngPair = ann.latLng?.let { raw ->
        val parts = raw.split(",")
        if (parts.size == 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lng = parts[1].trim().toDoubleOrNull()
            if (lat != null && lng != null) lat to lng else null
        } else null
    }
    return ImageAnnotation(
        uuid = ann.uuid,
        blockUuid = ann.blockUuid,
        pageUuid = ann.pageUuid,
        graphPath = ann.graphPath,
        filePath = ann.filePath,
        thumbnailPath = ann.thumbnailPath,
        source = runCatching { ImageSource.valueOf(ann.source) }.getOrDefault(ImageSource.FILE),
        sourceUri = ann.sourceUri,
        capturedAtMs = ann.capturedAtMs,
        importedAtMs = ann.importedAtMs,
        calibration = Calibration(
            method = runCatching { CalibrationMethod.valueOf(ann.calibrationMethod) }.getOrDefault(CalibrationMethod.NONE),
            pixelsPerMeter = ann.pixelsPerMeter,
            confidencePercent = ann.calibrationConfidencePct,
        ),
        unit = runCatching { MeasurementUnit.valueOf(ann.unit) }.getOrDefault(MeasurementUnit.METERS),
        tags = ann.tags,
        sensorData = ImageSensorData(
            latLng = latLngPair,
            altitudeM = ann.altitudeM,
            bearingDeg = ann.bearingDeg,
            pitchDeg = ann.pitchDeg,
            rollDeg = ann.rollDeg,
            focalLengthMm = ann.focalLengthMm,
            focalLength35mmEq = ann.focalLength35mmEq,
            cameraMake = ann.cameraMake,
            cameraModel = ann.cameraModel,
        ),
    )
}

fun SidecarFile.toDomainMeasurements(): List<MeasurementAnnotation> =
    measurements.map { m ->
        MeasurementAnnotation(
            uuid = m.uuid,
            imageUuid = m.imageUuid,
            annotationType = runCatching { AnnotationType.valueOf(m.annotationType) }.getOrDefault(AnnotationType.DISTANCE),
            normalizedPoints = m.normalizedPoints
                .filter { it.x in 0.0..1.0 && it.y in 0.0..1.0 }
                .map { NormalizedPoint(it.x, it.y) },
            valueMeters = m.valueMeters,
            valueDisplay = m.valueDisplay,
            label = m.label,
            colorHex = m.colorHex,
            bleDeviceId = m.bleDeviceId,
        )
    }
