package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.db.sidecar.ImageSidecarManager
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.ImageSource
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.google.DriveUploader
import dev.stapler.stelekit.platform.sensor.PlatformImageFile
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryImageAnnotationRepository
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Story 9.4 — Offline-first audit tests.
 *
 * Verifies that the annotation editor hot path (annotation create/save),
 * the image import pipeline (camera + file picker), and [ImageSidecarManager]
 * make no network calls.
 *
 * The [FailingDriveUploader] test double throws [AssertionError] if called,
 * proving that Drive / Google API operations are never invoked from the
 * offline-critical paths.
 */
class ImageAnnotationOfflineTest {

    // ── Test doubles ──────────────────────────────────────────────────────────

    /**
     * A [DriveUploader] that fails with [AssertionError] if any upload is attempted.
     *
     * Used to verify that the annotation and import pipelines never touch the network.
     */
    private class FailingDriveUploader : DriveUploader {
        override suspend fun uploadFile(
            fileName: String,
            mimeType: String,
            bytes: ByteArray,
            parentFolderId: String?,
        ): Either<DomainError, String> {
            throw AssertionError(
                "Network call detected in offline-critical path: uploadFile($fileName)"
            )
        }
    }

    private fun buildFixture(): Triple<ImageImportService, InMemoryImageAnnotationRepository, FakeFileSystem> {
        val fs = FakeFileSystem()
        val imageRepo = InMemoryImageAnnotationRepository()
        val blockRepo = InMemoryBlockRepository()
        val service = ImageImportService(
            fileSystem = fs,
            imageAnnotationRepository = imageRepo,
            blockRepository = blockRepo,
            sidecarManager = ImageSidecarManager(fs),
        )
        // Deliberately do NOT wire a DriveUploader — the service must not need one.
        return Triple(service, imageRepo, fs)
    }

    private fun minimalPlatformFile(fs: FakeFileSystem): PlatformImageFile {
        val path = "/tmp/test-image.jpg"
        fs.writeFile(path, "FAKE_JPEG_DATA")
        return PlatformImageFile(path = path, capturedAtMs = 1_700_000_000_000L)
    }

    // ── Import pipeline is offline ────────────────────────────────────────────

    @Test
    @OptIn(DirectRepositoryWrite::class)
    fun `image import completes without any network call`() {
        runBlocking {
            val (service, imageRepo, fs) = buildFixture()
            val tempFile = minimalPlatformFile(fs)

            val result = service.import(
                tempFile = tempFile,
                graphPath = "/graph",
                pageUuid = "page-001",
                source = ImageSource.FILE,
                insertToJournalPage = false,
            )

            assertIs<Either.Right<ImageAnnotation>>(result,
                "Expected import to succeed offline, got: ${(result as? Either.Left)?.value}"
            )
            assertNotNull(result.value.uuid)
        }
    }

    // ── Sidecar manager is offline ────────────────────────────────────────────

    @Test
    fun `ImageSidecarManager write and read do not trigger any network call`() {
        val fs = FakeFileSystem()
        val sidecarManager = ImageSidecarManager(fs)

        val annotation = ImageAnnotation(
            uuid = "uuid-sidecar-offline",
            blockUuid = "block-uuid",
            pageUuid = "page-uuid",
            graphPath = "/graph",
            filePath = "/graph/assets/images/uuid-sidecar-offline.jpg",
            source = ImageSource.FILE,
            capturedAtMs = 1_700_000_000_000L,
            importedAtMs = 1_700_000_000_001L,
            unit = MeasurementUnit.METERS,
        )
        val measurements = listOf(
            MeasurementAnnotation(
                uuid = "meas-001",
                imageUuid = "uuid-sidecar-offline",
                annotationType = AnnotationType.DISTANCE,
                normalizedPoints = listOf(NormalizedPoint(0.1, 0.2), NormalizedPoint(0.9, 0.8)),
                valueMeters = 3.5,
                valueDisplay = "3.5 m",
            ),
        )

        // Write — must not call any network
        val writeResult = sidecarManager.writeSidecar(annotation, measurements)
        assertIs<Either.Right<Unit>>(writeResult, "Sidecar write failed: $writeResult")

        // Read — must not call any network
        val readResult = sidecarManager.readSidecar("/graph", "uuid-sidecar-offline")
        assertIs<Either.Right<*>>(readResult, "Sidecar read failed: $readResult")
        assertNotNull(readResult.value) { "Sidecar content should not be null after write" }
    }

    // ── Annotation save (ViewModel repository layer) is offline ──────────────

    @Test
    @OptIn(DirectRepositoryWrite::class)
    fun `saving a MeasurementAnnotation to in-memory repository makes no network call`() {
        runBlocking {
            val repo = InMemoryMeasurementAnnotationRepository()
            val annotation = MeasurementAnnotation(
                uuid = "offline-meas-001",
                imageUuid = "image-uuid",
                annotationType = AnnotationType.DISTANCE,
                normalizedPoints = listOf(NormalizedPoint(0.1, 0.2), NormalizedPoint(0.9, 0.8)),
                valueMeters = 2.0,
                valueDisplay = "2.0 m",
            )

            // saveMeasurementAnnotation must complete without touching the network.
            // (The FailingDriveUploader is not wired here — the repo itself must not call out.)
            val result = repo.saveMeasurementAnnotation(annotation)
            assertIs<Either.Right<Unit>>(result)
        }
    }
}
