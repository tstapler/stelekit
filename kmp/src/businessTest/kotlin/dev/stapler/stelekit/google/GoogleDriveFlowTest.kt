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
import dev.stapler.stelekit.platform.google.AnnotatedImageRenderer
import dev.stapler.stelekit.platform.google.DriveExportService
import dev.stapler.stelekit.platform.google.DriveFile
import dev.stapler.stelekit.platform.google.DriveUploader
import dev.stapler.stelekit.platform.google.ZERO_BYTE_IMAGE_MESSAGE
import dev.stapler.stelekit.ui.annotate.AnnotationDriveExporter
import dev.stapler.stelekit.ui.annotate.DriveExportUiState
import dev.stapler.stelekit.ui.drive.DriveFileBrowserState
import dev.stapler.stelekit.ui.drive.DriveFileBrowserViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stories 8.2 and 8.3: Drive export, the zero-byte guard and the Drive browser, over fakes (no network). */
class GoogleDriveFlowTest {
    private val openViewModels = mutableListOf<DriveFileBrowserViewModel>()

    @AfterTest
    fun closeViewModels() = openViewModels.forEach { it.close() }

    // ── export ─────────────────────────────────────────────────────────────────

    private class RecordingUploader(private val failOn: String? = null) : DriveUploader {
        val uploads = mutableListOf<Triple<String, String, Int>>()
        override suspend fun uploadFile(fileName: String, mimeType: String, bytes: ByteArray, parentFolderId: String?): Either<DomainError, String> {
            if (failOn != null && fileName.endsWith(failOn)) return DomainError.NetworkError.HttpError(401, "token expired").left()
            uploads += Triple(fileName, mimeType, bytes.size)
            return "id-${uploads.size}".right()
        }
    }

    private class FixedRenderer(private val jpeg: Either<DomainError, ByteArray>) : AnnotatedImageRenderer {
        override suspend fun renderJpeg(imageAnnotation: ImageAnnotation, measurements: List<MeasurementAnnotation>) = jpeg
    }

    private val annotation = ImageAnnotation(
        uuid = "img1",
        blockUuid = "block1",
        pageUuid = "page1",
        graphPath = "/graph",
        filePath = "/graph/assets/photo.jpg",
        source = ImageSource.FILE,
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
    )

    @Test
    fun exportToDrive_should_UploadJpegAndSidecarAndReturnLink_When_ImageHasBytes() = runTest {
        val uploader = RecordingUploader()
        val exporter = AnnotationDriveExporter(DriveExportService(uploader), FixedRenderer(ByteArray(10).right()))

        exporter.export(annotation, emptyList())

        val state = assertIs<DriveExportUiState.Success>(exporter.state.value)
        assertEquals("https://drive.google.com/file/d/id-1/view", state.driveLink)
        assertEquals(listOf("img1_annotated.jpg" to "image/jpeg", "img1_annotated.measure.json" to "application/json"), uploader.uploads.map { it.first to it.second })
    }

    @Test
    fun exportToDrive_should_UploadNothingAndExplain_When_EncodedImageIsZeroBytes() = runTest {
        val uploader = RecordingUploader()
        val exporter = AnnotationDriveExporter(DriveExportService(uploader), FixedRenderer(ByteArray(0).right()))

        exporter.export(annotation, emptyList())

        assertEquals(DriveExportUiState.Error(ZERO_BYTE_IMAGE_MESSAGE), exporter.state.value)
        assertEquals("Couldn't encode the image - nothing was uploaded", ZERO_BYTE_IMAGE_MESSAGE)
        assertEquals(emptyList(), uploader.uploads)
    }

    @Test
    fun exportToDrive_should_ShowRenderErrorAndUploadNothing_When_RendererFails() = runTest {
        val uploader = RecordingUploader()
        val failing = FixedRenderer(DomainError.ExportError.EncodingFailed("$ZERO_BYTE_IMAGE_MESSAGE (decode)").left())
        val exporter = AnnotationDriveExporter(DriveExportService(uploader), failing)

        exporter.export(annotation, emptyList())

        assertIs<DriveExportUiState.Error>(exporter.state.value)
        assertEquals(emptyList(), uploader.uploads)
    }

    @Test
    fun exportToDrive_should_ShowUploadError_When_DriveRejectsTheToken() = runTest {
        val exporter = AnnotationDriveExporter(DriveExportService(RecordingUploader(failOn = ".jpg")), FixedRenderer(ByteArray(4).right()))

        exporter.export(annotation, emptyList())

        val error = assertIs<DriveExportUiState.Error>(exporter.state.value)
        assertTrue("token expired" in error.message)
    }

    @Test
    fun exportToDrive_should_ShowUploadingThenIdle_When_CancelledMidUpload() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow = object : AnnotatedImageRenderer {
            override suspend fun renderJpeg(imageAnnotation: ImageAnnotation, measurements: List<MeasurementAnnotation>): Either<DomainError, ByteArray> {
                gate.await()
                return ByteArray(3).right()
            }
        }
        val uploader = RecordingUploader()
        val exporter = AnnotationDriveExporter(DriveExportService(uploader), slow)
        val job = async { exporter.export(annotation, emptyList()) }
        advanceUntilIdle()
        assertEquals(DriveExportUiState.Loading, exporter.state.value)

        job.cancel()
        advanceUntilIdle()

        assertEquals(DriveExportUiState.Idle, exporter.state.value)
        assertEquals(emptyList(), uploader.uploads)
    }

    @Test
    fun exportToDrive_should_IgnoreSecondTap_When_AlreadyUploading() = runTest {
        val gate = CompletableDeferred<Unit>()
        var renders = 0
        val slow = object : AnnotatedImageRenderer {
            override suspend fun renderJpeg(imageAnnotation: ImageAnnotation, measurements: List<MeasurementAnnotation>): Either<DomainError, ByteArray> {
                renders++
                gate.await()
                return ByteArray(3).right()
            }
        }
        val exporter = AnnotationDriveExporter(DriveExportService(RecordingUploader()), slow)
        val first = async { exporter.export(annotation, emptyList()) }
        advanceUntilIdle()

        exporter.export(annotation, emptyList())
        exporter.reset()
        assertEquals(DriveExportUiState.Loading, exporter.state.value, "reset must not interrupt an upload")
        gate.complete(Unit)
        first.await()

        assertEquals(1, renders)
        assertIs<DriveExportUiState.Success>(exporter.state.value)
        exporter.reset()
        assertEquals(DriveExportUiState.Idle, exporter.state.value)
    }

    // ── browser ────────────────────────────────────────────────────────────────

    private fun browser(port: FakeDriveBrowser) = DriveFileBrowserViewModel(port).also { openViewModels += it }

    private fun settled(vm: DriveFileBrowserViewModel): DriveFileBrowserState = runBlocking {
        withTimeout(10_000) { vm.state.first { !it.isLoading } }
    }

    @Test
    fun browser_should_StartLoading_BeforeTheFirstListReturns() {
        val vm = browser(FakeDriveBrowser())
        assertTrue(vm.state.value.isLoading, "first render shows Loading…")
    }

    @Test
    fun browser_should_ListFiles_When_Loaded() {
        val file = DriveFile("f1", "notes.txt", "text/plain")
        val vm = browser(FakeDriveBrowser(files = listOf(file)))

        vm.loadFiles(null)

        val state = settled(vm)
        assertEquals(listOf(file), state.files)
        assertNull(state.error)
    }

    @Test
    fun browser_should_BeEmptyNotFailed_When_FolderHasNoFiles() {
        val vm = browser(FakeDriveBrowser())

        vm.loadFiles(null)

        val state = settled(vm)
        assertEquals(emptyList(), state.files)
        assertNull(state.error)
    }

    @Test
    fun browser_should_SayCouldntLoadDrive_When_ListingFails() {
        val vm = browser(FakeDriveBrowser(failure = DomainError.NetworkError.HttpError(500, "internal detail")))

        vm.loadFiles(null)

        val state = settled(vm)
        assertEquals("Couldn't load Drive", state.error)
        assertFalse(state.authExpired)
        assertFalse("internal detail" in state.error.orEmpty(), "server detail stays out of the UI copy")
    }

    @Test
    fun browser_should_PromptReAuth_When_TokenIsRejected() {
        val fake = FakeDriveBrowser(failure = DomainError.NetworkError.HttpError(401, "Not authenticated"))
        val vm = browser(fake)

        vm.loadFiles(null)

        val state = settled(vm)
        assertTrue(state.authExpired)
        assertEquals("Google sign-in expired - sign in again in Settings", state.error)

        fake.failure = null
        fake.files = listOf(DriveFile("f1", "a", "text/plain"))
        vm.reload()
        val recovered = settled(vm)
        assertFalse(recovered.authExpired)
        assertEquals(1, recovered.files.size)
    }

    @Test
    fun browser_should_NavigateIntoAndBackOutOfFolders() {
        val fake = FakeDriveBrowser(files = listOf(DriveFile("d1", "Docs", "application/vnd.google-apps.folder")))
        val vm = browser(fake)
        vm.loadFiles(null)
        settled(vm)

        vm.navigateInto(fake.files.single())
        settled(vm)
        assertEquals("My Drive / Docs", vm.state.value.breadcrumb)
        vm.navigateUp()
        settled(vm)

        assertEquals("My Drive", vm.state.value.breadcrumb)
        assertEquals(listOf(null, "d1", null), fake.listed)
    }
}
