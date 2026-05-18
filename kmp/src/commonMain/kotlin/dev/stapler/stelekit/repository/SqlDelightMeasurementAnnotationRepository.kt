package dev.stapler.stelekit.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.NormalizedPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SQLDelight-backed [MeasurementAnnotationRepository].
 *
 * Reads use [PlatformDispatcher.DB]; writes use [withContext] with [PlatformDispatcher.DB].
 * Follows the same pattern as [SqlDelightPageRepository]: calls steleDatabaseQueries directly.
 */
@OptIn(DirectRepositoryWrite::class)
class SqlDelightMeasurementAnnotationRepository(
    private val database: SteleDatabase,
) : MeasurementAnnotationRepository {

    private val queries = database.steleDatabaseQueries

    override fun getMeasurementsForImage(imageUuid: String): Flow<Either<DomainError, List<MeasurementAnnotation>>> =
        queries.selectMeasurementsForImage(imageUuid)
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { rows -> rows.map { it.toModel() }.right() }

    @DirectRepositoryWrite
    override suspend fun saveMeasurementAnnotation(measurement: MeasurementAnnotation): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.insertMeasurementAnnotation(
                    uuid = measurement.uuid,
                    image_uuid = measurement.imageUuid,
                    annotation_type = measurement.annotationType.name,
                    normalized_points = measurement.normalizedPoints.toJson(),
                    value_meters = measurement.valueMeters,
                    value_display = measurement.valueDisplay,
                    label = measurement.label,
                    color_hex = measurement.colorHex,
                    ble_device_id = measurement.bleDeviceId,
                )
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    @DirectRepositoryWrite
    override suspend fun saveMeasurements(imageUuid: String, measurements: List<MeasurementAnnotation>): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.transaction {
                    queries.deleteMeasurementsForImage(imageUuid)
                    measurements.forEach { m ->
                        queries.insertMeasurementAnnotation(
                            uuid = m.uuid,
                            image_uuid = m.imageUuid,
                            annotation_type = m.annotationType.name,
                            normalized_points = m.normalizedPoints.toJson(),
                            value_meters = m.valueMeters,
                            value_display = m.valueDisplay,
                            label = m.label,
                            color_hex = m.colorHex,
                            ble_device_id = m.bleDeviceId,
                        )
                    }
                }
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    @DirectRepositoryWrite
    override suspend fun deleteMeasurementAnnotation(uuid: String): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.deleteMeasurementAnnotation(uuid)
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    @DirectRepositoryWrite
    override suspend fun deleteMeasurementsForImage(imageUuid: String): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.deleteMeasurementsForImage(imageUuid)
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
}

// ── Serialization helpers ─────────────────────────────────────────────────────

private fun List<NormalizedPoint>.toJson(): String =
    joinToString(",", "[", "]") { """{"x":${it.x},"y":${it.y}}""" }

private fun isNormalizedCoordinate(v: Double?): Boolean = v != null && v in 0.0..1.0

private fun String.toNormalizedPoints(): List<NormalizedPoint> = try {
    val array = Json.parseToJsonElement(this) as JsonArray
    array.mapNotNull { element ->
        val obj: JsonObject = element.jsonObject
        val x = obj["x"]?.jsonPrimitive?.double
        val y = obj["y"]?.jsonPrimitive?.double
        if (isNormalizedCoordinate(x) && isNormalizedCoordinate(y)) {
            NormalizedPoint(x!!, y!!)
        } else null
    }
} catch (_: Exception) {
    emptyList()
}

private fun dev.stapler.stelekit.db.Measurement_annotations.toModel(): MeasurementAnnotation =
    MeasurementAnnotation(
        uuid = uuid,
        imageUuid = image_uuid,
        annotationType = runCatching { AnnotationType.valueOf(annotation_type) }.getOrDefault(AnnotationType.DISTANCE),
        normalizedPoints = normalized_points.toNormalizedPoints(),
        valueMeters = value_meters,
        valueDisplay = value_display,
        label = label,
        colorHex = color_hex,
        bleDeviceId = ble_device_id,
    )
