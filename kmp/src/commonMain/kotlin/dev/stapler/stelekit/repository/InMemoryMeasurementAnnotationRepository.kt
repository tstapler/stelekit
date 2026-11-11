package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.MeasurementAnnotation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [MeasurementAnnotationRepository] backed by a [MutableStateFlow].
 *
 * Stores measurements keyed by their own [MeasurementAnnotation.uuid].
 * Suitable for unit tests and non-SQLDelight graph backends.
 */
@OptIn(DirectRepositoryWrite::class)
class InMemoryMeasurementAnnotationRepository : MeasurementAnnotationRepository {

    private val measurements = MutableStateFlow<Map<String, MeasurementAnnotation>>(emptyMap())

    override fun getMeasurementsForImage(imageUuid: String): Flow<Either<DomainError, List<MeasurementAnnotation>>> =
        measurements.map { map ->
            map.values
                .filter { it.imageUuid == imageUuid }
                .right()
        }

    @DirectRepositoryWrite
    override suspend fun saveMeasurementAnnotation(measurement: MeasurementAnnotation): Either<DomainError, Unit> {
        val current = measurements.value.toMutableMap()
        current[measurement.uuid] = measurement
        measurements.value = current
        return Unit.right()
    }

    @DirectRepositoryWrite
    override suspend fun saveMeasurements(imageUuid: String, newMeasurements: List<MeasurementAnnotation>): Either<DomainError, Unit> {
        val current = measurements.value.toMutableMap()
        // Remove all existing measurements for this image
        current.entries.removeAll { it.value.imageUuid == imageUuid }
        // Insert new measurements
        newMeasurements.forEach { current[it.uuid] = it }
        measurements.value = current
        return Unit.right()
    }

    @DirectRepositoryWrite
    override suspend fun deleteMeasurementAnnotation(uuid: String): Either<DomainError, Unit> {
        val current = measurements.value.toMutableMap()
        current.remove(uuid)
        measurements.value = current
        return Unit.right()
    }

    @DirectRepositoryWrite
    override suspend fun deleteMeasurementsForImage(imageUuid: String): Either<DomainError, Unit> {
        val current = measurements.value.toMutableMap()
        current.entries.removeAll { it.value.imageUuid == imageUuid }
        measurements.value = current
        return Unit.right()
    }
}
