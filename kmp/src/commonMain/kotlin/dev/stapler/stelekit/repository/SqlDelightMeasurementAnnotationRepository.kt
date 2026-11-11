package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.RestrictedDatabaseQueries
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.NormalizedPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
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
 * Writes go through [MeasurementWrites] (DatabaseWriteActor, HIGH priority).
 */
@OptIn(DirectSqlWrite::class, DirectRepositoryWrite::class)
class SqlDelightMeasurementAnnotationRepository(
    private val database: SteleDatabase,
    private val writes: MeasurementWrites = MeasurementWrites(null),
) : MeasurementAnnotationRepository {

    private val queries = database.steleDatabaseQueries
    private val restricted = RestrictedDatabaseQueries(queries)

    /** Runs [body] as one actor-serialized write on the DB dispatcher, mapping failures to WriteFailed. */
    private suspend fun write(body: suspend () -> Unit): Either<DomainError, Unit> =
        writes.execute {
            withContext(PlatformDispatcher.DB) {
                try {
                    body()
                    Unit.right()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
                }
            }
        }

    override fun getMeasurementsForImage(imageUuid: String): Flow<Either<DomainError, List<MeasurementAnnotation>>> =
        queries.selectMeasurementsForImage(imageUuid)
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    @DirectRepositoryWrite
    override suspend fun saveMeasurementAnnotation(measurement: MeasurementAnnotation): Either<DomainError, Unit> =
        write { restricted.insertMeasurement(measurement) }

    @DirectRepositoryWrite
    override suspend fun saveMeasurements(imageUuid: String, measurements: List<MeasurementAnnotation>): Either<DomainError, Unit> =
        write {
            restricted.transaction {
                restricted.deleteMeasurementsForImage(imageUuid)
                measurements.forEach { restricted.insertMeasurement(it) }
            }
        }

    @DirectRepositoryWrite
    override suspend fun deleteMeasurementAnnotation(uuid: String): Either<DomainError, Unit> =
        write { restricted.deleteMeasurementAnnotation(uuid) }

    @DirectRepositoryWrite
    override suspend fun deleteMeasurementsForImage(imageUuid: String): Either<DomainError, Unit> =
        write { restricted.deleteMeasurementsForImage(imageUuid) }
}

@OptIn(DirectSqlWrite::class)
private suspend fun RestrictedDatabaseQueries.insertMeasurement(m: MeasurementAnnotation) {
    insertMeasurementAnnotation(
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
