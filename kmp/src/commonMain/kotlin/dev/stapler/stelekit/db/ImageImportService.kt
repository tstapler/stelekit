package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.sidecar.ImageSidecarManager
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.ImageSensorData
import dev.stapler.stelekit.model.ImageSource
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.sensor.PlatformImageFile
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.ImageAnnotationRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.repository.MeasurementAnnotationRepository
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt
import kotlin.time.Clock

/**
 * Orchestrates the full image import pipeline for Epic 2.
 *
 * Import flow:
 * 1. Reserve a stable file path in `<graph>/assets/images/`
 * 2. Copy image bytes from the temp [PlatformImageFile] path to the reserved path
 * 3. Create an [ImageAnnotation] domain object
 * 4. Write the JSON sidecar FIRST (per Known Issues transactional write order)
 * 5. Save the [ImageAnnotation] to SQLDelight via the repository
 * 6. Create an `image_annotation` [Block] on the target page
 * 7. Optionally auto-insert the block into today's journal page
 *
 * Step 4 before step 5 is critical: the sidecar is the authoritative source of truth.
 * If the sidecar write fails, the DB row is never written and the error is returned.
 */
class ImageImportService(
    private val fileSystem: FileSystem,
    private val imageAnnotationRepository: ImageAnnotationRepository? = null,
    private val blockRepository: BlockRepository? = null,
    private val sidecarManager: ImageSidecarManager? = null,
    private val journalService: JournalService? = null,
    private val writeActor: DatabaseWriteActor? = null,
    private val measurementAnnotationRepository: MeasurementAnnotationRepository? = null,
) {

    /**
     * Reserve the target directory for a new image with [uuid] in [graphPath].
     *
     * Creates the `assets/images/` directory tree if it does not yet exist.
     *
     * Returns the resolved file path that the caller should write image bytes to.
     */
    fun reservePath(graphPath: String, uuid: String): Either<DomainError, String> {
        val dir = ImageStoragePathResolver.assetsImagesDir(graphPath)
        ensureDirectory("$graphPath/assets")
        ensureDirectory(dir)
        return ImageStoragePathResolver.resolvePath(graphPath, uuid).right()
    }

    /**
     * Full import pipeline: copy bytes → create annotation → write sidecar → save DB → create block.
     *
     * @param tempFile       Platform-obtained image file ready for import.
     * @param graphPath      Absolute path (or saf:// URI) of the target graph.
     * @param pageUuid       UUID of the page that will own the new block.
     * @param source         How the image was obtained ([ImageSource.CAMERA], [ImageSource.FILE], etc).
     * @param insertToJournalPage If `true` AND [journalService] is wired in, also appends the
     *                       block content to today's journal page.
     * @return The persisted [ImageAnnotation] on success, or a [DomainError] on failure.
     */
    @OptIn(DirectRepositoryWrite::class)
    suspend fun import(
        tempFile: PlatformImageFile,
        graphPath: String,
        pageUuid: String,
        source: ImageSource = ImageSource.FILE,
        insertToJournalPage: Boolean = false,
    ): Either<DomainError, ImageAnnotation> {
        val annotationUuid = UuidGenerator.generateV7()
        val blockUuid = UuidGenerator.generateV7()
        val now = Clock.System.now()

        // Step 1: Reserve stable path and ensure directory exists
        val destPath = reservePath(graphPath, annotationUuid)
            .fold({ return it.left() }, { it })

        // Step 2: Copy bytes from temp location to graph assets
        copyImageBytes(tempFile.path, destPath).fold(
            ifLeft = { return it.left() },
            ifRight = { /* success — continue */ },
        )

        // Step 3: Build the domain object.
        // Merge EXIF data from PlatformImageFile with motion sensor data (GPS, bearing,
        // pitch/roll) captured at the moment of image capture (Story 8.1.5).
        val capturedSensorData = tempFile.sensorData
        val annotation = ImageAnnotation(
            uuid = annotationUuid,
            blockUuid = blockUuid,
            pageUuid = pageUuid,
            graphPath = graphPath,
            filePath = destPath,
            source = source,
            capturedAtMs = tempFile.capturedAtMs,
            importedAtMs = now.toEpochMilliseconds(),
            unit = MeasurementUnit.METERS,
            sensorData = ImageSensorData(
                // GPS + motion sensor data from MotionSensorProvider snapshot at capture time
                latLng = capturedSensorData?.latLng,
                altitudeM = capturedSensorData?.altitudeM,
                bearingDeg = capturedSensorData?.bearingDeg,
                pitchDeg = capturedSensorData?.pitchDeg,
                rollDeg = capturedSensorData?.rollDeg,
                // EXIF data from the image file
                focalLengthMm = tempFile.focalLengthMm ?: capturedSensorData?.focalLengthMm,
                focalLength35mmEq = tempFile.focalLength35mmEq ?: capturedSensorData?.focalLength35mmEq,
                cameraMake = tempFile.cameraMake ?: capturedSensorData?.cameraMake,
                cameraModel = tempFile.cameraModel ?: capturedSensorData?.cameraModel,
            ),
        )

        // Step 4: Write sidecar BEFORE DB insert (Known Issues transactional write order)
        val resolvedSidecarManager = sidecarManager ?: ImageSidecarManager(fileSystem)
        resolvedSidecarManager.writeSidecar(annotation, emptyList()).fold(
            ifLeft = { err ->
                // Sidecar failed — roll back the copied file and return error
                fileSystem.deleteFile(destPath)
                return err.left()
            },
            ifRight = { /* continue */ },
        )

        // Step 5: Save annotation to DB
        val imageRepo = imageAnnotationRepository
            ?: return DomainError.DatabaseError.WriteFailed(
                "ImageAnnotationRepository not wired — cannot persist annotation"
            ).left()
        imageRepo.saveImageAnnotation(annotation).fold(
            ifLeft = { err ->
                // DB write failed; sidecar already written. Leave sidecar in place —
                // ImageSidecarIndexer can recover the DB row from the sidecar on next startup.
                return err.left()
            },
            ifRight = { /* continue */ },
        )

        // Step 6: Create the image_annotation block
        val filename = destPath.substringAfterLast("/")
        val relPath = "../assets/images/$filename"
        val blockContent = "![]($relPath)"
        val blockProperties = mapOf(
            "image-id" to annotationUuid,
            "calibration" to "none",
            "unit" to annotation.unit.name.lowercase(),
        )
        val block = Block(
            uuid = blockUuid,
            pageUuid = pageUuid,
            content = blockContent,
            position = 0,
            createdAt = now,
            updatedAt = now,
            properties = blockProperties,
            blockType = "image_annotation",
        )

        saveBlock(block).fold(
            ifLeft = { err -> return err.left() },
            ifRight = { /* continue */ },
        )

        // Step 6b: Auto-create compass bearing annotation (Story 8.3)
        // If bearing data is present in sensor data, create an initial LABEL annotation
        // positioned at the top-right corner of the image (~0.85, 0.05 normalized coords).
        val bearingDeg = annotation.sensorData.bearingDeg
        if (bearingDeg != null) {
            val bearingInt = bearingDeg.roundToInt()
            val bearingLabel = "Bearing: ${bearingInt}°N"
            val bearingAnnotation = MeasurementAnnotation(
                uuid = UuidGenerator.generateV7(),
                imageUuid = annotationUuid,
                annotationType = AnnotationType.LABEL,
                normalizedPoints = listOf(NormalizedPoint(0.85, 0.05)),
                label = bearingLabel,
            )
            try {
                measurementAnnotationRepository?.saveMeasurementAnnotation(bearingAnnotation)
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (_: Exception) {
                // Non-fatal: bearing annotation failure must not fail the import
            }
        }

        // Step 7: Auto-insert into today's journal page (camera import, Story 2.3.3)
        if (insertToJournalPage && source == ImageSource.CAMERA) {
            try {
                journalService?.appendToToday(blockContent)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Non-fatal: journal append failure must not fail the import
            }
        }

        return annotation.right()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Copy raw bytes from [srcPath] to [destPath].
     *
     * Uses [FileSystem.readFileBytes] and [FileSystem.writeFileBytes] so the bytes
     * never pass through a String decode/encode cycle.
     */
    private fun copyImageBytes(srcPath: String, destPath: String): Either<DomainError, Unit> {
        return try {
            val bytes = fileSystem.readFileBytes(srcPath)
                ?: return DomainError.FileSystemError.NotFound(srcPath).left()
            val written = fileSystem.writeFileBytes(destPath, bytes)
            if (written) Unit.right()
            else DomainError.FileSystemError.WriteFailed(destPath, "writeFileBytes returned false").left()
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnsupportedOperationException) {
            // Platform hasn't implemented readFileBytes/writeFileBytes — attempt text copy as fallback
            copyBytesViaText(srcPath, destPath)
        } catch (e: Exception) {
            DomainError.FileSystemError.WriteFailed(destPath, e.message ?: "unknown").left()
        }
    }

    /** Text-path fallback for platforms without byte IO. */
    private fun copyBytesViaText(srcPath: String, destPath: String): Either<DomainError, Unit> {
        return try {
            val content = fileSystem.readFile(srcPath)
                ?: return DomainError.FileSystemError.NotFound(srcPath).left()
            val written = fileSystem.writeFile(destPath, content)
            if (written) Unit.right()
            else DomainError.FileSystemError.WriteFailed(destPath, "writeFile returned false").left()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.FileSystemError.WriteFailed(destPath, e.message ?: "unknown").left()
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun saveBlock(block: Block): Either<DomainError, Unit> {
        val actor = writeActor
        val repo = blockRepository
        return when {
            actor != null -> actor.saveBlock(block)
            repo != null -> repo.saveBlock(block)
            else -> DomainError.DatabaseError.WriteFailed(
                "BlockRepository not wired — cannot create image_annotation block"
            ).left()
        }
    }

    private fun ensureDirectory(path: String) {
        if (!fileSystem.directoryExists(path)) {
            fileSystem.createDirectory(path)
        }
    }
}
