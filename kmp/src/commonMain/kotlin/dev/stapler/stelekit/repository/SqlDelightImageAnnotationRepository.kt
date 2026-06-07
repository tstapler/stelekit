package dev.stapler.stelekit.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.ImageSensorData
import dev.stapler.stelekit.model.ImageSource
import dev.stapler.stelekit.model.MeasurementUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * SQLDelight-backed [ImageAnnotationRepository].
 *
 * All reads use [PlatformDispatcher.DB] via [mapToList]/[mapToOneOrNull].
 * All writes use [withContext] with [PlatformDispatcher.DB].
 *
 * Follows the same pattern as [SqlDelightPageRepository]: calls steleDatabaseQueries
 * directly (not RestrictedDatabaseQueries, which is the actor-internal gate).
 */
@OptIn(DirectRepositoryWrite::class)
class SqlDelightImageAnnotationRepository(
    private val database: SteleDatabase,
) : ImageAnnotationRepository {

    private val queries = database.steleDatabaseQueries

    override fun getAllImageAnnotations(): Flow<Either<DomainError, List<ImageAnnotation>>> =
        queries.selectAllImageAnnotations()
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { rows -> rows.map { it.toModel() }.right() }
            .catchDbError()

    override fun getImageAnnotationByUuid(uuid: String): Flow<Either<DomainError, ImageAnnotation?>> =
        queries.selectImageAnnotationByUuid(uuid)
            .asFlow()
            .mapToOneOrNull(PlatformDispatcher.DB)
            .map { row -> row?.toModel().right() }
            .catchDbError()

    override fun getImageAnnotationsByPage(pageUuid: String): Flow<Either<DomainError, List<ImageAnnotation>>> =
        queries.selectImageAnnotationsByPage(pageUuid)
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { rows -> rows.map { it.toModel() }.right() }
            .catchDbError()

    override fun getImageAnnotationsByTag(tag: String): Flow<Either<DomainError, List<ImageAnnotation>>> =
        queries.selectImageAnnotationsByTag(tag)
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { rows -> rows.map { it.toModel() }.right() }
            .catchDbError()

    @DirectRepositoryWrite
    override suspend fun saveImageAnnotation(annotation: ImageAnnotation): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                val tagsJson = annotation.tags.joinToString(",", "[", "]") { "\"$it\"" }
                val latLng = annotation.sensorData.latLng?.let { (lat, lng) -> "$lat,$lng" }
                queries.insertImageAnnotation(
                    uuid = annotation.uuid,
                    block_uuid = annotation.blockUuid,
                    page_uuid = annotation.pageUuid,
                    graph_path = annotation.graphPath,
                    file_path = annotation.filePath,
                    thumbnail_path = annotation.thumbnailPath,
                    source = annotation.source.name,
                    source_uri = annotation.sourceUri,
                    captured_at_ms = annotation.capturedAtMs,
                    imported_at_ms = annotation.importedAtMs,
                    calibration_method = annotation.calibration.method.name,
                    pixels_per_meter = annotation.calibration.pixelsPerMeter,
                    calibration_confidence_pct = annotation.calibration.confidencePercent.toLong(),
                    unit = annotation.unit.name,
                    tags = tagsJson,
                    lat_lng = latLng,
                    altitude_m = annotation.sensorData.altitudeM,
                    bearing_deg = annotation.sensorData.bearingDeg,
                    pitch_deg = annotation.sensorData.pitchDeg,
                    roll_deg = annotation.sensorData.rollDeg,
                    focal_length_mm = annotation.sensorData.focalLengthMm,
                    focal_length_35mm_eq = annotation.sensorData.focalLength35mmEq,
                    camera_make = annotation.sensorData.cameraMake,
                    camera_model = annotation.sensorData.cameraModel,
                )
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    @DirectRepositoryWrite
    override suspend fun deleteImageAnnotation(uuid: String): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.deleteImageAnnotation(uuid)
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
}

private fun <T> Flow<Either<DomainError, T>>.catchDbError(): Flow<Either<DomainError, T>> =
    catch { e ->
        if (e is CancellationException) throw e
        emit(DomainError.DatabaseError.ReadFailed(e.message ?: "database closed").left())
    }

// ── Row → domain model mapper ─────────────────────────────────────────────────

private fun dev.stapler.stelekit.db.Image_annotations.toModel(): ImageAnnotation {
    val latLngPair = lat_lng?.let { raw ->
        val parts = raw.split(",")
        if (parts.size == 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lng = parts[1].trim().toDoubleOrNull()
            if (lat != null && lng != null) lat to lng else null
        } else null
    }

    val tagsList: List<String> = try {
        val element = Json.parseToJsonElement(tags)
        element.jsonArray.map { it.jsonPrimitive.content }
    } catch (_: Exception) {
        emptyList()
    }

    return ImageAnnotation(
        uuid = uuid,
        blockUuid = block_uuid,
        pageUuid = page_uuid,
        graphPath = graph_path,
        filePath = file_path,
        thumbnailPath = thumbnail_path,
        source = runCatching { ImageSource.valueOf(source) }.getOrDefault(ImageSource.FILE),
        sourceUri = source_uri,
        capturedAtMs = captured_at_ms,
        importedAtMs = imported_at_ms,
        calibration = Calibration(
            method = runCatching { CalibrationMethod.valueOf(calibration_method) }.getOrDefault(CalibrationMethod.NONE),
            pixelsPerMeter = pixels_per_meter,
            confidencePercent = calibration_confidence_pct.toInt(),
        ),
        unit = runCatching { MeasurementUnit.valueOf(unit) }.getOrDefault(MeasurementUnit.METERS),
        tags = tagsList,
        sensorData = ImageSensorData(
            latLng = latLngPair,
            altitudeM = altitude_m,
            bearingDeg = bearing_deg,
            pitchDeg = pitch_deg,
            rollDeg = roll_deg,
            focalLengthMm = focal_length_mm,
            focalLength35mmEq = focal_length_35mm_eq,
            cameraMake = camera_make,
            cameraModel = camera_model,
        ),
    )
}
