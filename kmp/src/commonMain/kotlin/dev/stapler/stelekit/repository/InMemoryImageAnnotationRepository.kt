package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ImageAnnotation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [ImageAnnotationRepository] backed by a [MutableStateFlow].
 *
 * Suitable for unit tests and non-SQLDelight graph backends. Thread-safe via immutable
 * copy-on-write updates to the state flow value.
 */
@OptIn(DirectRepositoryWrite::class)
class InMemoryImageAnnotationRepository : ImageAnnotationRepository {

    private val annotations = MutableStateFlow<Map<String, ImageAnnotation>>(emptyMap())

    override fun getAllImageAnnotations(): Flow<Either<DomainError, List<ImageAnnotation>>> =
        annotations.map { map ->
            map.values.sortedByDescending { it.importedAtMs }.right()
        }

    override fun getImageAnnotationByUuid(uuid: String): Flow<Either<DomainError, ImageAnnotation?>> =
        annotations.map { map -> map[uuid].right() }

    override fun getImageAnnotationsByPage(pageUuid: String): Flow<Either<DomainError, List<ImageAnnotation>>> =
        annotations.map { map ->
            map.values
                .filter { it.pageUuid == pageUuid }
                .sortedByDescending { it.importedAtMs }
                .right()
        }

    override fun getImageAnnotationsByTag(tag: String): Flow<Either<DomainError, List<ImageAnnotation>>> =
        annotations.map { map ->
            map.values
                .filter { tag in it.tags }
                .sortedByDescending { it.importedAtMs }
                .right()
        }

    @DirectRepositoryWrite
    override suspend fun saveImageAnnotation(annotation: ImageAnnotation): Either<DomainError, Unit> {
        val current = annotations.value.toMutableMap()
        if (annotation.uuid in current) {
            return DomainError.ValidationError.ConstraintViolation(
                "ImageAnnotation with uuid ${annotation.uuid} already exists"
            ).left()
        }
        current[annotation.uuid] = annotation
        annotations.value = current
        return Unit.right()
    }

    @DirectRepositoryWrite
    override suspend fun deleteImageAnnotation(uuid: String): Either<DomainError, Unit> {
        val current = annotations.value.toMutableMap()
        current.remove(uuid)
        annotations.value = current
        return Unit.right()
    }

    /** Update an existing annotation (used in tests where duplicate uuid must succeed). */
    fun upsert(annotation: ImageAnnotation) {
        val current = annotations.value.toMutableMap()
        current[annotation.uuid] = annotation
        annotations.value = current
    }
}
