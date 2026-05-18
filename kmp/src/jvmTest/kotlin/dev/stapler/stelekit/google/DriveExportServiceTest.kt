package dev.stapler.stelekit.google

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.ImageSource
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.google.DriveExportService
import dev.stapler.stelekit.platform.google.DriveUploader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [DriveExportService].
 *
 * Verifies that a successful export uploads exactly two files:
 * 1. An annotated JPEG image
 * 2. A .measure.json sidecar
 *
 * Uses a [DriveUploader] fake — no real HTTP calls.
 */
class DriveExportServiceTest {

    private val uploadCalls = mutableListOf<UploadCall>()
    private var shouldFailUpload = false

    private val fakeUploader = object : DriveUploader {
        private var idCounter = 0
        override suspend fun uploadFile(
            fileName: String,
            mimeType: String,
            bytes: ByteArray,
            parentFolderId: String?,
        ): Either<DomainError, String> {
            return if (shouldFailUpload) {
                DomainError.NetworkError.HttpError(503, "Mock upload failure").left()
            } else {
                uploadCalls.add(UploadCall(fileName, mimeType, bytes.size, parentFolderId))
                "mock-file-id-${++idCounter}".right()
            }
        }
    }

    private val service = DriveExportService(fakeUploader)

    private val imageAnnotation = ImageAnnotation(
        uuid = "imgUuid001",
        blockUuid = "blkUuid001",
        pageUuid = "pageUuid001",
        graphPath = "/graphs/test",
        filePath = "/graphs/test/assets/images/test.jpg",
        source = ImageSource.CAMERA,
        calibration = Calibration(
            method = CalibrationMethod.MANUAL_REFERENCE,
            pixelsPerMeter = 200.0,
            confidencePercent = 100,
        ),
        unit = MeasurementUnit.METERS,
    )

    private val measurements = listOf(
        MeasurementAnnotation(
            uuid = "measUuid001",
            imageUuid = "imgUuid001",
            annotationType = AnnotationType.DISTANCE,
            normalizedPoints = listOf(NormalizedPoint(0.1, 0.2), NormalizedPoint(0.5, 0.6)),
            valueMeters = 2.5,
            valueDisplay = "2.5 m",
        ),
        MeasurementAnnotation(
            uuid = "measUuid002",
            imageUuid = "imgUuid001",
            annotationType = AnnotationType.AREA,
            normalizedPoints = listOf(
                NormalizedPoint(0.1, 0.1),
                NormalizedPoint(0.5, 0.1),
                NormalizedPoint(0.5, 0.5),
            ),
            valueMeters = 4.0,
            valueDisplay = "4.0 m²",
        ),
    )

    @Test
    fun `exportToDrive uploads exactly two files`() = runTest {
        val result = service.exportToDrive(imageAnnotation, measurements, ByteArray(1024))
        assertIs<Either.Right<*>>(result)
        assertEquals(2, uploadCalls.size, "Expected exactly 2 uploads: annotated JPEG + sidecar JSON")
    }

    @Test
    fun `exportToDrive uploads annotated JPEG`() = runTest {
        service.exportToDrive(imageAnnotation, measurements, ByteArray(512))
        val jpegUpload = uploadCalls.firstOrNull { it.mimeType == "image/jpeg" }
        assertTrue(jpegUpload != null, "Expected a JPEG upload call")
        assertTrue(
            jpegUpload.fileName.endsWith("_annotated.jpg"),
            "JPEG filename must end with _annotated.jpg, got ${jpegUpload.fileName}",
        )
    }

    @Test
    fun `exportToDrive uploads JSON sidecar`() = runTest {
        service.exportToDrive(imageAnnotation, measurements, ByteArray(512))
        val sidecarUpload = uploadCalls.firstOrNull { it.mimeType == "application/json" }
        assertTrue(sidecarUpload != null, "Expected a JSON sidecar upload call")
        assertTrue(
            sidecarUpload.fileName.endsWith(".measure.json"),
            "Sidecar filename must end with .measure.json, got ${sidecarUpload.fileName}",
        )
    }

    @Test
    fun `exportToDrive returns error when upload fails`() = runTest {
        shouldFailUpload = true
        val result = service.exportToDrive(imageAnnotation, measurements, ByteArray(100))
        assertIs<Either.Left<*>>(result)
        assertIs<DomainError.NetworkError.HttpError>((result as Either.Left).value)
    }

    @Test
    fun `exportToDrive passes folderId to all uploads`() = runTest {
        val folderId = "folderTest123"
        service.exportToDrive(imageAnnotation, measurements, ByteArray(100), folderId = folderId)
        uploadCalls.forEach { call ->
            assertEquals(folderId, call.parentFolderId, "Both uploads should target folder $folderId")
        }
    }

    @Test
    fun `exportToDrive result contains non-blank file IDs`() = runTest {
        val result = service.exportToDrive(imageAnnotation, measurements, ByteArray(100))
        val exportResult = (result as Either.Right).value
        assertTrue(exportResult.imageFileId.isNotBlank(), "imageFileId must not be blank")
        assertTrue(exportResult.sidecarFileId.isNotBlank(), "sidecarFileId must not be blank")
    }

    @Test
    fun `exportToDrive image filename contains annotation UUID`() = runTest {
        service.exportToDrive(imageAnnotation, measurements, ByteArray(100))
        val jpegUpload = uploadCalls.firstOrNull { it.mimeType == "image/jpeg" }
        assertTrue(
            jpegUpload?.fileName?.contains(imageAnnotation.uuid) == true,
            "JPEG filename should contain image UUID for traceability",
        )
    }

    @Test
    fun `exportToDrive without folderId passes null parentFolderId`() = runTest {
        service.exportToDrive(imageAnnotation, measurements, ByteArray(100), folderId = null)
        uploadCalls.forEach { call ->
            assertEquals(null, call.parentFolderId, "Without folderId, parentFolderId should be null")
        }
    }
}

// ── Test data class ───────────────────────────────────────────────────────────

private data class UploadCall(
    val fileName: String,
    val mimeType: String,
    val bytesSize: Int,
    val parentFolderId: String?,
)
