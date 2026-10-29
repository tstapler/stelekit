// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.google

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.platform.google.AnnotatedImageRenderer
import dev.stapler.stelekit.platform.google.DriveBrowserPort
import dev.stapler.stelekit.platform.google.DriveFile
import dev.stapler.stelekit.platform.google.DriveUploader
import dev.stapler.stelekit.platform.google.GoogleAuthManager
import dev.stapler.stelekit.platform.google.GoogleIntegration
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.ui.DriveBrowserRoute
import dev.stapler.stelekit.ui.annotate.AnnotationEditorScreen
import dev.stapler.stelekit.ui.annotate.AnnotationEditorViewModel
import dev.stapler.stelekit.ui.annotate.DriveExportUiState
import dev.stapler.stelekit.ui.rememberGraphContentDriveExportActions
import dev.stapler.stelekit.wiring.ObservableEffect
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stories 8.2 and 8.3 (Robolectric): the Drive browser route and the annotation editor's
 * "Export to Drive" action, composed through the same functions `ScreenRouter` calls, over a
 * fake Google client. Nothing here touches the network, so the real-account run is plan item 12.5.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DriveEntryPointTest {
    @get:Rule
    val rule = createComposeRule()

    private class FakeAuth(private val signedIn: Boolean) : GoogleAuthManager {
        override suspend fun authenticate(): Either<DomainError, String> = "me@example.com".right()
        override suspend fun signOut() {}
        override suspend fun isAuthenticated() = signedIn
        override suspend fun getConnectedEmail(): String? = null
    }

    private class FakeDrive : DriveUploader, DriveBrowserPort {
        val uploads = mutableListOf<String>()
        var files: List<DriveFile> = emptyList()
        var failure: DomainError? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun uploadFile(fileName: String, mimeType: String, bytes: ByteArray, parentFolderId: String?): Either<DomainError, String> {
            uploads += fileName
            return "file-${uploads.size}".right()
        }
        override suspend fun listFiles(folderId: String?): Either<DomainError, List<DriveFile>> {
            gate?.await()
            return failure?.left() ?: files.right()
        }
        override suspend fun downloadFile(fileId: String): Either<DomainError, ByteArray> = ByteArray(1).right()
    }

    private class FakeRenderer(var jpeg: ByteArray = ByteArray(8), var gate: CompletableDeferred<Unit>? = null) : AnnotatedImageRenderer {
        override suspend fun renderJpeg(imageAnnotation: ImageAnnotation, measurements: List<MeasurementAnnotation>): Either<DomainError, ByteArray> {
            gate?.await()
            return jpeg.right()
        }
    }

    private val drive = FakeDrive()
    private val renderer = FakeRenderer()

    private fun google(signedIn: Boolean = true) =
        GoogleIntegration(FakeAuth(signedIn), drive, drive, photosPicker = null, imageRenderer = renderer)

    // ── browser ────────────────────────────────────────────────────────────────

    @Test
    fun browser_showsLoadingThenFiles() {
        val gate = CompletableDeferred<Unit>()
        drive.gate = gate
        drive.files = listOf(DriveFile("f1", "survey.measure.json", "application/json"))
        rule.setContent { DriveBrowserRoute(google(), onNavigateBack = {}) }
        rule.waitForIdle()
        rule.onNodeWithText("Loading…").assertExists()

        gate.complete(Unit)
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("survey.measure.json") > 0 }

        rule.onNodeWithText("My Drive").assertExists()
    }

    @Test
    fun browser_showsNoFilesForAnEmptyDrive() {
        rule.setContent { DriveBrowserRoute(google(), onNavigateBack = {}) }

        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("No files") > 0 }
    }

    @Test
    fun browser_showsCouldntLoadWithRetry_andRecoversOnRetry() {
        drive.failure = DomainError.NetworkError.HttpError(500, "boom")
        rule.setContent { DriveBrowserRoute(google(), onNavigateBack = {}) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Couldn't load Drive") > 0 }

        drive.failure = null
        drive.files = listOf(DriveFile("f1", "after-retry.txt", "text/plain"))
        rule.onNodeWithText("Retry").performClick()

        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("after-retry.txt") > 0 }
    }

    @Test
    fun browser_asksForSignInAgain_whenTheTokenIsRejected() {
        drive.failure = DomainError.NetworkError.HttpError(401, "Not authenticated")
        rule.setContent { DriveBrowserRoute(google(), onNavigateBack = {}) }

        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Google sign-in expired - sign in again in Settings") > 0 }
    }

    @Test
    fun browser_leavesWhenThisBuildHasNoGoogleSurfaces() {
        var left = 0
        rule.setContent { DriveBrowserRoute(google = null, onNavigateBack = { left++ }) }
        rule.waitForIdle()

        assertEquals(1, left)
    }

    // ── export ─────────────────────────────────────────────────────────────────

    private val image = ImageAnnotation(
        uuid = "img-drive-001",
        blockUuid = "blk-drive-001",
        pageUuid = "page-drive-001",
        graphPath = "/test",
        filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
    )

    private fun editorWithExport(google: GoogleIntegration?) {
        val vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(image) }
        rule.setContent { EditorWithDriveExport(google, vm) }
        rule.waitForIdle()
    }

    @Composable
    private fun EditorWithDriveExport(google: GoogleIntegration?, vm: AnnotationEditorViewModel) {
        val export = rememberGraphContentDriveExportActions(google, vm, image)
        AnnotationEditorScreen(
            viewModel = vm,
            imageAnnotation = image,
            onExportToDrive = export?.onExport,
            driveExportState = export?.state ?: DriveExportUiState.Idle,
            onCancelDriveExport = export?.onCancel,
            driveExportDisabledReason = export?.disabledReason,
        )
    }

    @Test
    fun export_uploadsJpegAndSidecar_andShowsTheDriveLink() {
        editorWithExport(google())
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Sign in to Google in Settings") == 0 }

        rule.onNodeWithContentDescription("Export to Google Drive").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Saved to Drive") > 0 }

        ObservableEffect.assert("drive-export-uploads") {
            assertEquals(listOf("img-drive-001_annotated.jpg", "img-drive-001_annotated.measure.json"), drive.uploads)
        }
        rule.onNodeWithText("https://drive.google.com/file/d/file-1/view").assertExists()
    }

    @Test
    fun export_refusesAZeroByteImage_andUploadsNothing() {
        renderer.jpeg = ByteArray(0)
        editorWithExport(google())
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Sign in to Google in Settings") == 0 }

        rule.onNodeWithContentDescription("Export to Google Drive").performClick()

        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Couldn't encode the image - nothing was uploaded") > 0 }
        assertTrue(drive.uploads.isEmpty())
    }

    @Test
    fun export_isDisabledWithAReason_whenSignedOut() {
        editorWithExport(google(signedIn = false))

        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Sign in to Google in Settings") > 0 }
        rule.onNodeWithContentDescription("Export to Google Drive").performClick()
        rule.waitForIdle()

        assertTrue(drive.uploads.isEmpty())
    }

    @Test
    fun export_showsUploadingWithCancel_andCancelStopsIt() {
        val gate = CompletableDeferred<Unit>()
        renderer.gate = gate
        editorWithExport(google())
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Sign in to Google in Settings") == 0 }

        rule.onNodeWithContentDescription("Export to Google Drive").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Uploading…") > 0 }
        rule.onNodeWithText("Cancel").performClick()

        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Uploading…") == 0 }
        assertTrue(drive.uploads.isEmpty())
        rule.onNodeWithContentDescription("Export to Google Drive").assertExists()
    }

    @Test
    fun export_isHiddenWithoutAnImageRenderer() {
        editorWithExport(GoogleIntegration(FakeAuth(true), drive, drive))

        rule.onNodeWithContentDescription("Export to Google Drive").assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size
}
