package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ImageAnnotation
import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to [ImageAnnotation] entities.
 *
 * All reads are reactive [Flow]-returning operations backed by SQLDelight-generated queries.
 * All writes are [Either]-returning `suspend fun` gated behind [DirectRepositoryWrite].
 *
 * Production implementation: [SqlDelightImageAnnotationRepository].
 * Test implementation: [InMemoryImageAnnotationRepository].
 */
interface ImageAnnotationRepository {

    /** Emit all annotations across all graphs and pages, ordered by import date descending. */
    fun getAllImageAnnotations(): Flow<Either<DomainError, List<ImageAnnotation>>>

    /** Emit a single annotation by [uuid], or `null` if not found. */
    fun getImageAnnotationByUuid(uuid: String): Flow<Either<DomainError, ImageAnnotation?>>

    /** Emit all annotations for a specific page. */
    fun getImageAnnotationsByPage(pageUuid: String): Flow<Either<DomainError, List<ImageAnnotation>>>

    /** Emit all annotations that contain [tag] in their tag list. */
    fun getImageAnnotationsByTag(tag: String): Flow<Either<DomainError, List<ImageAnnotation>>>

    /**
     * Persist [annotation]. INSERT if uuid is new; REPLACE if uuid already exists.
     *
     * Callers must write the JSON sidecar BEFORE calling this method to maintain
     * transactional order (sidecar is authoritative source of truth).
     */
    @DirectRepositoryWrite
    suspend fun saveImageAnnotation(annotation: ImageAnnotation): Either<DomainError, Unit>

    /** Remove the annotation and all child [MeasurementAnnotation] rows (cascade). */
    @DirectRepositoryWrite
    suspend fun deleteImageAnnotation(uuid: String): Either<DomainError, Unit>
}
