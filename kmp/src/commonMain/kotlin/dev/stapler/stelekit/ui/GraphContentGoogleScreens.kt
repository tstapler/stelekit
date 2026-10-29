// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.platform.google.DriveExportService
import dev.stapler.stelekit.platform.google.GoogleIntegration
import dev.stapler.stelekit.platform.google.GooglePhotosImportController
import dev.stapler.stelekit.service.MediaAttachmentService
import dev.stapler.stelekit.service.toMarkdown
import dev.stapler.stelekit.ui.components.GooglePhotosEntry
import dev.stapler.stelekit.platform.openInBrowser
import dev.stapler.stelekit.ui.annotate.AnnotationDriveExporter
import dev.stapler.stelekit.ui.annotate.AnnotationEditorViewModel
import dev.stapler.stelekit.ui.annotate.DriveExportUiState
import dev.stapler.stelekit.ui.drive.DriveFileBrowserScreen
import dev.stapler.stelekit.ui.drive.DriveFileBrowserViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal const val SIGN_IN_TO_GOOGLE_REASON = "Sign in to Google in Settings"

/**
 * Reads the stored sign-in state. Throwable (not just Exception): an unavailable Android Keystore
 * surfaces as an error out of the token store and must read as "signed out", not crash the screen.
 */
private suspend fun GoogleIntegration.isSignedIn(): Boolean = try {
    authManager.isAuthenticated()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Throwable) {
    false
}

/** What the annotation editor's "Export to Drive" button needs; see [rememberGraphContentDriveExportActions]. */
internal class DriveExportActions(
    val state: DriveExportUiState,
    /** Non-null while signed out. */
    val disabledReason: String?,
    val onExport: () -> Unit,
    val onCancel: () -> Unit,
)

/**
 * Builds the Drive export actions for the annotation editor, or null when this build/platform
 * exposes no Drive export (no [GoogleIntegration], or no image renderer) so the button stays hidden.
 */
@Composable
internal fun rememberGraphContentDriveExportActions(
    google: GoogleIntegration?,
    editorViewModel: AnnotationEditorViewModel,
    annotation: ImageAnnotation,
): DriveExportActions? {
    val renderer = google?.imageRenderer ?: return null
    val exporter = remember(google) { AnnotationDriveExporter(DriveExportService(google.driveUploader), renderer) }
    val state by exporter.state.collectAsState()
    val signedIn by produceState(false, google) { value = google.isSignedIn() }
    val scope = rememberCoroutineScope()
    var running by remember(exporter) { mutableStateOf<Job?>(null) }
    DisposableEffect(exporter) { onDispose { running?.cancel() } }
    return DriveExportActions(
        state = state,
        disabledReason = if (signedIn) null else SIGN_IN_TO_GOOGLE_REASON,
        onExport = {
            running = scope.launch {
                exporter.export(annotation, editorViewModel.state.value.committedAnnotations)
            }
        },
        onCancel = { running?.cancel() },
    )
}

/**
 * Builds the "From Google Photos" toolbar entry, or null when this build/platform has no Photos
 * picker or the platform has no attachment service to save into. Imported photos go through
 * [attachments] (the asset-indexing decorator in the shipped root) and are inserted with
 * [insertMarkdown] at the editing block. [signInRecheckKey] re-reads the sign-in state when it
 * changes (e.g. the Settings dialog closing after a connect).
 */
@Composable
internal fun rememberGraphContentGooglePhotosEntry(
    google: GoogleIntegration?,
    attachments: MediaAttachmentService?,
    notesPath: EffectiveNotesPath?,
    signInRecheckKey: Any?,
    insertMarkdown: (BlockUuid, String) -> Unit,
): GooglePhotosEntry? {
    val picker = google?.photosPicker ?: return null
    if (attachments == null) return null
    val controller = remember(picker) { GooglePhotosImportController(picker) }
    val status by controller.state.collectAsState()
    val signedIn by produceState(false, google, status, signInRecheckKey) { value = google.isSignedIn() }
    val scope = rememberCoroutineScope()
    val currentInsert by rememberUpdatedState(insertMarkdown)
    val currentNotesPath by rememberUpdatedState(notesPath)
    return GooglePhotosEntry(
        signedIn = signedIn,
        status = status,
        onImport = { blockUuid ->
            val notes = currentNotesPath
            if (notes != null) {
                scope.launch {
                    val saved = controller.import(attachments, notes)
                    if (saved.isNotEmpty()) currentInsert(blockUuid, saved.joinToString("\n") { it.toMarkdown() })
                }
            }
        },
        onDismissFailure = controller::dismiss,
    )
}

/** The `Screen.DriveBrowser` route: a Drive file list over [GoogleIntegration.driveBrowser]. */
@Composable
internal fun DriveBrowserRoute(google: GoogleIntegration?, onNavigateBack: () -> Unit) {
    if (google == null) {
        // A saved navigation entry from a build that had Google surfaces; there is nothing to show.
        LaunchedEffect(Unit) { onNavigateBack() }
        return
    }
    val browserViewModel = remember(google) { DriveFileBrowserViewModel(google.driveBrowser) }
    DisposableEffect(browserViewModel) { onDispose { browserViewModel.close() } }
    DriveFileBrowserScreen(
        viewModel = browserViewModel,
        onNavigateBack = onNavigateBack,
        onFileSelect = { file -> openInBrowser("https://drive.google.com/file/d/${file.id}/view") },
    )
}
