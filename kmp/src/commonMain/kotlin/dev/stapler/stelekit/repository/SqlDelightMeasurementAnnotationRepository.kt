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
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementValue
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.ValueSource
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
@OptIn(DirectRepositoryWrite::class)
class SqlDelightMeasurementAnnotationRepository(
    private val database: SteleDatabase,
    private val writes: MeasurementWrites = MeasurementWrites(null),
) : MeasurementAnnotationRepository {

    private val queries = database.steleDatabaseQueries
    private val restricted = RestrictedDatabaseQueries(queries)

    /** Runs [op] as one actor-serialized write on the DB dispatcher, mapping failures to WriteFailed. */
    private suspend fun write(op: WriteOp): Either<DomainError, Unit> =
        writes.execute {
            withContext(PlatformDispatcher.DB) {
                try {
                    apply(op)
                    Unit.right()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
                }
            }
        }

    /** The only place this repository touches mutating queries; always called inside an actor op. */
    @OptIn(DirectSqlWrite::class)
    private suspend fun apply(op: WriteOp) {
        suspend fun insert(m: MeasurementAnnotation): Long {
            val (source, original) = m.value?.toColumns() ?: (ValueSource.COMPUTED to null)
            return restricted.insertMeasurementAnnotation(
                uuid = m.uuid,
                image_uuid = m.imageUuid,
                annotation_type = m.annotationType.name,
                normalized_points = m.normalizedPoints.toJson(),
                value_meters = m.valueMeters,
                value_display = m.valueDisplay,
                label = m.label,
                color_hex = m.colorHex,
                ble_device_id = m.bleDeviceId,
                value_source = source.name,
                value_original_meters = original,
            )
        }
        when (op) {
            is WriteOp.Save -> insert(op.measurement)
            is WriteOp.ReplaceForImage -> restricted.transaction {
                restricted.deleteMeasurementsForImage(op.imageUuid)
                op.measurements.forEach { insert(it) }
            }
            is WriteOp.Delete -> restricted.deleteMeasurementAnnotation(op.uuid)
            is WriteOp.DeleteForImage -> restricted.deleteMeasurementsForImage(op.imageUuid)
        }
    }

    private sealed interface WriteOp {
        class Save(val measurement: MeasurementAnnotation) : WriteOp
        class ReplaceForImage(val imageUuid: String, val measurements: List<MeasurementAnnotation>) : WriteOp
        class Delete(val uuid: String) : WriteOp
        class DeleteForImage(val imageUuid: String) : WriteOp
    }

    override fun getMeasurementsForImage(imageUuid: String): Flow<Either<DomainError, List<MeasurementAnnotation>>> =
        queries.selectMeasurementsForImage(imageUuid)
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    @DirectRepositoryWrite
    override suspend fun saveMeasurementAnnotation(measurement: MeasurementAnnotation): Either<DomainError, Unit> =
        write(WriteOp.Save(measurement))

    @DirectRepositoryWrite
    override suspend fun saveMeasurements(imageUuid: String, measurements: List<MeasurementAnnotation>): Either<DomainError, Unit> =
        write(WriteOp.ReplaceForImage(imageUuid, measurements))

    @DirectRepositoryWrite
    override suspend fun deleteMeasurementAnnotation(uuid: String): Either<DomainError, Unit> =
        write(WriteOp.Delete(uuid))

    @DirectRepositoryWrite
    override suspend fun deleteMeasurementsForImage(imageUuid: String): Either<DomainError, Unit> =
        write(WriteOp.DeleteForImage(imageUuid))
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

private fun MeasurementValue.toColumns(): Pair<ValueSource, Double?> = when (this) {
    is MeasurementValue.Computed -> ValueSource.COMPUTED to null
    is MeasurementValue.Overridden -> ValueSource.USER_OVERRIDE to originalMeters
}

private val logger = Logger("MeasurementAnnotationRepo")

/** Reads the value triple through [MeasurementValue.fromColumns] so an illegal pair never reaches the domain. */
private fun dev.stapler.stelekit.db.Measurement_annotations.toModel(): MeasurementAnnotation {
    val source = runCatching { ValueSource.valueOf(value_source) }.getOrDefault(ValueSource.COMPUTED)
    val value = MeasurementValue.fromColumns(value_meters, source, value_original_meters)
    val legal = (source == ValueSource.USER_OVERRIDE) == (value_original_meters != null) || value_meters == null
    if (!legal || value_source != source.name) {
        logger.warn("normalised measurement value columns uuid=$uuid source=$value_source original=$value_original_meters")
    }
    return MeasurementAnnotation(
        uuid = uuid,
        imageUuid = image_uuid,
        annotationType = runCatching { AnnotationType.valueOf(annotation_type) }.getOrDefault(AnnotationType.DISTANCE),
        normalizedPoints = normalized_points.toNormalizedPoints(),
        valueMeters = value_meters,
        valueDisplay = value_display,
        label = label,
        colorHex = color_hex,
        bleDeviceId = ble_device_id,
        valueSource = if (value is MeasurementValue.Overridden) ValueSource.USER_OVERRIDE else ValueSource.COMPUTED,
        originalValueMeters = (value as? MeasurementValue.Overridden)?.originalMeters,
    )
}
