package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.MeasurementAnnotation
import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to [MeasurementAnnotation] entities.
 *
 * Measurement annotations are child records of [ImageAnnotation]s.  Deleting the parent
 * image annotation cascades to delete all of its measurements at the DB layer.
 *
 * Production implementation: [SqlDelightMeasurementAnnotationRepository].
 * Test implementation: [InMemoryMeasurementAnnotationRepository].
 */
interface MeasurementAnnotationRepository {

    /** Emit all measurements for a single image annotation, ordered by insertion. */
    fun getMeasurementsForImage(imageUuid: String): Flow<Either<DomainError, List<MeasurementAnnotation>>>

    /**
     * Persist [measurement]. INSERT OR REPLACE semantics.
     */
    @DirectRepositoryWrite
    suspend fun saveMeasurementAnnotation(measurement: MeasurementAnnotation): Either<DomainError, Unit>

    /**
     * Save a batch of measurements for [imageUuid] atomically.
     *
     * Replaces all existing measurements for the image before inserting the new set.
     * Callers should prefer this over individual [saveMeasurementAnnotation] calls when
     * committing a full annotation session.
     */
    @DirectRepositoryWrite
    suspend fun saveMeasurements(imageUuid: String, measurements: List<MeasurementAnnotation>): Either<DomainError, Unit>

    /** Delete a single measurement by its own [uuid]. */
    @DirectRepositoryWrite
    suspend fun deleteMeasurementAnnotation(uuid: String): Either<DomainError, Unit>

    /** Delete all measurements for [imageUuid] (used before re-saving a complete set). */
    @DirectRepositoryWrite
    suspend fun deleteMeasurementsForImage(imageUuid: String): Either<DomainError, Unit>
}
