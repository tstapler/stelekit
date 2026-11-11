package dev.stapler.stelekit.repository

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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    override fun getImageAnnotationByUuid(uuid: String): Flow<Either<DomainError, ImageAnnotation?>> =
        queries.selectImageAnnotationByUuid(uuid)
            .asDbFlowOrNull(PlatformDispatcher.DB) { it.toModel() }

    override fun getImageAnnotationsByPage(pageUuid: String): Flow<Either<DomainError, List<ImageAnnotation>>> =
        queries.selectImageAnnotationsByPage(pageUuid)
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    override fun getImageAnnotationsByTag(tag: String): Flow<Either<DomainError, List<ImageAnnotation>>> =
        // The SQL LIKE is only a prefilter (a quoted-literal substring can still hit inside another
        // tag's escaped text, and % / _ in the tag act as wildcards); the exact test is on decoded tags.
        queries.selectImageAnnotationsByTag(encodeTagContent(tag))
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }
            .map { result -> result.map { rows -> rows.filter { tag in it.tags } } }

    @DirectRepositoryWrite
    override suspend fun saveImageAnnotation(annotation: ImageAnnotation): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                val tagsJson = encodeTags(annotation.tags)
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
                    calibration_basis_version = annotation.calibration.basisVersion.toLong(),
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

    val tagsList: List<String> = decodeTags(tags)

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
            basisVersion = calibration_basis_version.toInt(),
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

private val tagListSerializer = ListSerializer(String.serializer())

internal fun encodeTags(tags: List<String>): String = Json.encodeToString(tagListSerializer, tags)

/** The JSON-escaped text of [tag] without its surrounding quotes, as it appears inside the stored array. */
internal fun encodeTagContent(tag: String): String =
    Json.encodeToString(String.serializer(), tag).drop(1).dropLast(1)

/**
 * Decodes the stored tags column. Rows written before escaping was fixed may hold invalid JSON
 * (an unescaped quote or backslash in a tag); those are salvaged by splitting on the `","` joins
 * the old writer produced instead of being dropped.
 */
internal fun decodeTags(raw: String): List<String> =
    try {
        Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
    } catch (_: Exception) {
        val body = raw.trim().removeSurrounding("[", "]").trim()
        if (body.length < 2 || !body.startsWith('"') || !body.endsWith('"')) emptyList()
        else body.substring(1, body.length - 1).split("\",\"")
    }
