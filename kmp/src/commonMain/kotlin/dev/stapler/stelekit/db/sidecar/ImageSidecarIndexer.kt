package dev.stapler.stelekit.db.sidecar

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.ImageAnnotationRepository
import dev.stapler.stelekit.repository.MeasurementAnnotationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Rebuilds the SQLDelight [image_annotations] and [measurement_annotations] tables by
 * walking all `*.measure.json` sidecar files in `<graphPath>/.stelekit/images/`.
 *
 * This is the **recovery path** used when the SQLite database is lost (e.g. git clean,
 * accidental deletion, or DB corruption). The sidecar files are the authoritative source.
 *
 * Call [rebuildFromSidecars] at graph-open time if the DB is empty but sidecar files exist.
 */
class ImageSidecarIndexer(
    private val fileSystem: FileSystem,
    private val imageAnnotationRepository: ImageAnnotationRepository,
    private val measurementAnnotationRepository: MeasurementAnnotationRepository,
) {
    private val logger = Logger("ImageSidecarIndexer")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Scan all `.measure.json` files under `<graphPath>/.stelekit/images/` and upsert
     * each into the repository.
     *
     * Returns the number of sidecars successfully upserted, or [DomainError] on a
     * hard failure (individual parse errors are logged and skipped).
     */
    @OptIn(DirectRepositoryWrite::class)
    suspend fun rebuildFromSidecars(graphPath: String): Either<DomainError, Int> {
        val imagesDir = "$graphPath/.stelekit/images"
        if (!fileSystem.directoryExists(imagesDir)) {
            logger.info("ImageSidecarIndexer: no sidecar directory at $imagesDir, nothing to rebuild")
            return 0.right()
        }

        val files = try {
            fileSystem.listFiles(imagesDir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DomainError.FileSystemError.ReadFailed(imagesDir, e.message ?: "unknown").left()
        }

        var upserted = 0
        for (fileName in files) {
            if (!fileName.endsWith(".measure.json")) continue
            val fullPath = "$imagesDir/$fileName"
            try {
                val content = fileSystem.readFile(fullPath) ?: continue
                val sidecar = json.decodeFromString(SidecarFile.serializer(), content)
                val annotation = sidecar.toDomainAnnotation()
                val measurements = sidecar.toDomainMeasurements()

                // Upsert: delete first (no-op if absent), then insert
                imageAnnotationRepository.deleteImageAnnotation(annotation.uuid)
                imageAnnotationRepository.saveImageAnnotation(annotation).onLeft { err ->
                    logger.warn("ImageSidecarIndexer: failed to upsert annotation ${annotation.uuid}: ${err.message}")
                    return@onLeft
                }
                measurementAnnotationRepository.deleteMeasurementsForImage(annotation.uuid)
                measurementAnnotationRepository.saveMeasurements(annotation.uuid, measurements)
                upserted++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("ImageSidecarIndexer: skipping malformed sidecar $fullPath: ${e.message}")
            }
        }

        logger.info("ImageSidecarIndexer: rebuilt $upserted image annotations from sidecars")
        return upserted.right()
    }
}
