// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.withContext
import dev.stapler.stelekit.error.toUiMessage
import dev.stapler.stelekit.ui.components.LocalQueryBlockContext
import dev.stapler.stelekit.ui.components.QueryBlockContext
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.ImageSource
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.sensor.PlatformImageFile
import dev.stapler.stelekit.platform.sensor.SensorModule
import dev.stapler.stelekit.service.AttachmentResult
import dev.stapler.stelekit.service.DroppedFileBytes
import dev.stapler.stelekit.service.MediaAttachmentService
import dev.stapler.stelekit.service.markdownImageLink
import dev.stapler.stelekit.service.toMarkdown
import dev.stapler.stelekit.ui.components.CameraViewfinderDialog
import dev.stapler.stelekit.ui.components.CapturePreviewDialog
import dev.stapler.stelekit.ui.components.EditorCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError

/** In-flight camera-capture state, shared by [GraphContentScreenAndCapture] and [GraphContentCaptureDialogs]. */
private class CaptureState(
    val pendingCaptureFile: MutableState<PlatformImageFile?> = mutableStateOf(null),
    val pendingCapturePageUuid: MutableState<String?> = mutableStateOf(null),
    val pendingCaptureBlockUuid: MutableState<BlockUuid?> = mutableStateOf(null),
    val isCaptureImporting: MutableState<Boolean> = mutableStateOf(false),
    val showCameraViewfinder: MutableState<Boolean> = mutableStateOf(false),
    val pendingCaptureNavigateAfterImport: MutableState<Boolean> = mutableStateOf(false),
)

/** Whether the camera-import entry points (`onCaptureImage`/`onImportImage`) should be enabled at all. */
private fun cameraImportEnabled(inputs: GraphContentMainAreaInputs): Boolean =
    inputs.graphIoStack.imageImportService != null && SensorModule.cameraProvider.isAvailable

/** [ScreenRouter] plus the camera-capture dialogs it can trigger via [EditorCapabilities]. */
@Composable
internal fun GraphContentScreenAndCapture(deps: GraphContentDeps, viewModel: StelekitViewModel, inputs: GraphContentMainAreaInputs) {
    val captureState = remember { CaptureState() }
    val diagnosticsCollector = remember(deps.graphManager, deps.fileSystem, deps.repos, deps.platformSettings) {
        dev.stapler.stelekit.diagnostics.GraphDiagnosticsCollector(
            deps.graphManager, deps.fileSystem, deps.repos, deps.platformSettings,
        )
    }

    // Re-derived from deps.repos, so a graph switch rebinds every QueryBlock to the new graph.
    // The flag read is a synchronous SQLite query, so it runs on the DB dispatcher, not during composition.
    val queryBlocksEnabled by produceState(initialValue = true, deps.repos) {
        value = withContext(PlatformDispatcher.DB) {
            // A graph switch/close can invalidate the flag DB mid-read; fall back to the default.
            try {
                deps.repos.debugFlagRepository?.getFlag("live_query_blocks", default = true) ?: true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                true
            }
        }
    }
    val queryContext = remember(deps.repos, queryBlocksEnabled) {
        QueryBlockContext(
            executor = deps.repos.queryExecutor,
            pageRepository = deps.repos.pageRepository,
            enabled = queryBlocksEnabled,
        )
    }
    CompositionLocalProvider(
        LocalQueryBlockContext provides queryContext,
    ) {
        ScreenRouter(
            screen = inputs.appState.currentScreen,
            repos = deps.repos,
            blockStateManager = inputs.viewModelStack.blockStateManager,
            journalsViewModel = inputs.supportingViewModels.journalsViewModel,
            allPagesViewModel = inputs.supportingViewModels.allPagesViewModel,
            libraryStatsViewModel = inputs.supportingViewModels.libraryStatsViewModel,
            viewModel = viewModel,
            searchViewModel = inputs.supportingViewModels.searchViewModel,
            notificationManager = deps.notificationManager,
            appState = inputs.appState,
            graphWriter = inputs.graphIoStack.graphWriter,
            urlFetcher = deps.coreServices.urlFetcher,
            qrTransferSettings = inputs.tagVoiceStack.qrTransferSettings,
            graphLoader = inputs.graphIoStack.graphLoader,
            graphDiagnostics = diagnosticsCollector::collect,
            capabilities = buildEditorCapabilities(deps, viewModel, inputs, captureState),
            onImportImage = buildOnImportImage(deps, viewModel, inputs, captureState),
            platformSettings = deps.platformSettings,
            perfSpans = inputs.perfTelemetry.perfSpans,
            perfHistograms = inputs.perfTelemetry.perfHistograms,
            perfQueryStats = inputs.perfTelemetry.perfQueryStats,
            tagSuggestionViewModel = inputs.tagVoiceStack.tagSuggestionViewModel,
        )
    }

    GraphContentCaptureDialogs(deps, viewModel, inputs, captureState)
}

private fun buildEditorCapabilities(
    deps: GraphContentDeps,
    viewModel: StelekitViewModel,
    inputs: GraphContentMainAreaInputs,
    captureState: CaptureState,
): EditorCapabilities {
    val attachmentService = deps.platformIntegrations.attachmentService
    return EditorCapabilities(
        onAttachImage = attachmentService?.let { service ->
            { editingBlockUuid: BlockUuid ->
                inputs.scope.launch { attachImageToBlock(service, inputs, editingBlockUuid) }
            }
        },
        onFileDrop = attachmentService?.let { service -> { files -> onFileDrop(service, inputs, files) } },
        onPasteImage = attachmentService?.let { service ->
            { editingBlockUuid: BlockUuid? -> tryPasteImage(service, inputs, editingBlockUuid) }
        },
        onCaptureImage = buildOnCaptureImage(deps, viewModel, inputs, captureState),
    )
}

private fun tryPasteImage(
    attachmentService: MediaAttachmentService,
    inputs: GraphContentMainAreaInputs,
    editingBlockUuid: BlockUuid?,
): Boolean {
    if (!attachmentService.hasClipboardImage()) return false
    inputs.scope.launch { pasteImageToBlock(attachmentService, inputs, editingBlockUuid) }
    return true
}

private fun buildOnCaptureImage(
    deps: GraphContentDeps,
    viewModel: StelekitViewModel,
    inputs: GraphContentMainAreaInputs,
    captureState: CaptureState,
): (() -> Unit)? {
    if (!cameraImportEnabled(inputs)) return null
    return { inputs.scope.launch { startCameraCapture(deps, viewModel, inputs, captureState) } }
}

private suspend fun attachImageToBlock(
    attachmentService: MediaAttachmentService,
    inputs: GraphContentMainAreaInputs,
    editingBlockUuid: BlockUuid,
) {
    val graphRoot = inputs.appState.currentGraphPath ?: return
    val result = attachmentService.pickAndAttach(graphRoot = graphRoot, pageRelativePath = "") ?: return
    result.fold(
        ifLeft = { err -> inputs.graphContentLogger.warn("Image attachment failed: $err") },
        ifRight = { attachment -> inputs.viewModelStack.blockStateManager.insertTextAtCursor(editingBlockUuid, attachment.toMarkdown()) },
    )
}

private fun onFileDrop(
    attachmentService: MediaAttachmentService,
    inputs: GraphContentMainAreaInputs,
    files: List<Any>,
) {
    val graphRoot = inputs.appState.currentGraphPath
    val pageUuid = (inputs.appState.currentScreen as? Screen.PageView)?.page?.uuid
    if (pageUuid == null || graphRoot == null) return
    inputs.scope.launch {
        handleFileDrop(
            files = files,
            attachmentService = attachmentService,
            graphRoot = graphRoot,
            onAttached = { markdown ->
                inputs.viewModelStack.blockStateManager.addBlockWithContent(pageUuid = pageUuid, content = markdown)
            },
            onError = { err -> inputs.graphContentLogger.warn("Drag-and-drop attachment failed: $err") },
        )
    }
}

/**
 * Pure drag-and-drop attach logic — factored out of `GraphContent`'s `onFileDrop` wiring (PR #361
 * Gate-2 review) so the per-file dispatch and success/failure handling is unit-testable without
 * mounting the Compose tree, mirroring [addGraphFlowMode]. Iterates [files], attaching each one
 * via [attachmentService] ([DroppedFileBytes] goes through `attachBytes`, everything else (a
 * platform file-path token) through `attachFilePath`), then invokes [onAttached] with the
 * resulting markdown on success or [onError] with the [DomainError] on failure. A `null` result
 * (the platform doesn't support that attach path) is skipped silently, matching the
 * pre-extraction behavior.
 */
internal suspend fun handleFileDrop(
    files: List<Any>,
    attachmentService: MediaAttachmentService,
    graphRoot: String,
    onAttached: (markdown: String) -> Unit,
    onError: (DomainError) -> Unit,
) {
    files.forEach { file ->
        val result = when (file) {
            is DroppedFileBytes -> attachmentService.attachBytes(
                bytes = file.bytes,
                suggestedName = file.suggestedName,
                graphRoot = graphRoot,
            )
            else -> attachmentService.attachFilePath(
                filePath = file.toString(),
                graphRoot = graphRoot,
            )
        } ?: return@forEach
        result.fold(
            ifLeft = onError,
            ifRight = { attachment: AttachmentResult -> onAttached(attachment.toMarkdown()) },
        )
    }
}

private suspend fun pasteImageToBlock(
    attachmentService: MediaAttachmentService,
    inputs: GraphContentMainAreaInputs,
    editingBlockUuid: BlockUuid?,
) {
    val graphRoot = inputs.appState.currentGraphPath ?: return
    val result = attachmentService.pasteFromClipboard(graphRoot) ?: return
    result.fold(
        ifLeft = { err -> inputs.graphContentLogger.warn("Clipboard paste failed: $err") },
        ifRight = { attachment ->
            if (editingBlockUuid != null) {
                inputs.viewModelStack.blockStateManager.insertTextAtCursor(editingBlockUuid, attachment.toMarkdown())
            }
        },
    )
}

private suspend fun requestCameraPermissionIfNeeded(deps: GraphContentDeps, viewModel: StelekitViewModel): Boolean {
    val requestCameraPermission = deps.platformIntegrations.requestCameraPermission ?: return true
    val granted = requestCameraPermission.invoke()
    if (!granted) viewModel.sendSnackbar("Camera permission denied — enable it in Settings to take photos")
    return granted
}

/** [EditorCapabilities.onCaptureImage] — opens the viewfinder for the currently open page/block. */
private suspend fun startCameraCapture(
    deps: GraphContentDeps,
    viewModel: StelekitViewModel,
    inputs: GraphContentMainAreaInputs,
    captureState: CaptureState,
) {
    if (!requestCameraPermissionIfNeeded(deps, viewModel)) return
    // Snapshot page/block context before entering viewfinder — user may be in the editor and
    // these will be stale once the dialog opens.
    val pageUuid = (inputs.appState.currentScreen as? Screen.PageView)?.page?.uuid?.value
        ?: inputs.appState.currentPage?.uuid?.value
    captureState.pendingCapturePageUuid.value = pageUuid ?: deps.repos.journalService.ensureTodayJournal().uuid.value
    captureState.pendingCaptureBlockUuid.value = inputs.viewModelStack.blockStateManager.editingBlockUuid.value
    captureState.pendingCaptureNavigateAfterImport.value = false
    captureState.showCameraViewfinder.value = true
}

/** `ScreenRouter.onImportImage` — the "import from journal" (not editing a specific block) capture entry point. */
private fun buildOnImportImage(
    deps: GraphContentDeps,
    viewModel: StelekitViewModel,
    inputs: GraphContentMainAreaInputs,
    captureState: CaptureState,
): (() -> Unit)? {
    if (!cameraImportEnabled(inputs)) return null
    return { inputs.scope.launch { importImageToJournal(deps, viewModel, captureState) } }
}

private suspend fun importImageToJournal(deps: GraphContentDeps, viewModel: StelekitViewModel, captureState: CaptureState) {
    if (!requestCameraPermissionIfNeeded(deps, viewModel)) return
    val page = deps.repos.journalService.ensureTodayJournal()
    captureState.pendingCapturePageUuid.value = page.uuid.value
    captureState.pendingCaptureBlockUuid.value = null
    captureState.pendingCaptureNavigateAfterImport.value = true
    captureState.showCameraViewfinder.value = true
}

/** The camera viewfinder dialog, and (once a photo is taken) the save/discard preview dialog. */
@Composable
private fun GraphContentCaptureDialogs(
    deps: GraphContentDeps,
    viewModel: StelekitViewModel,
    inputs: GraphContentMainAreaInputs,
    captureState: CaptureState,
) {
    if (captureState.showCameraViewfinder.value) {
        CameraViewfinderDialog(
            onCapture = { file ->
                captureState.pendingCaptureFile.value = file
                captureState.showCameraViewfinder.value = false
            },
            onDismiss = { captureState.showCameraViewfinder.value = false },
            onError = { msg -> viewModel.sendSnackbar(msg) },
        )
    }
    val captureFile = captureState.pendingCaptureFile.value
    val capturePageUuid = captureState.pendingCapturePageUuid.value
    if (captureFile != null && capturePageUuid != null) {
        CapturePreviewDialog(
            imagePath = captureFile.path,
            isImporting = captureState.isCaptureImporting.value,
            onSave = {
                captureState.isCaptureImporting.value = true
                val photo = CapturedPhoto(captureFile, capturePageUuid)
                inputs.scope.launch { saveCapturedImage(deps, viewModel, inputs, captureState, photo) }
            },
            onDiscard = { clearPendingCapture(captureState) },
        )
    }
}

private fun clearPendingCapture(captureState: CaptureState) {
    captureState.pendingCaptureFile.value = null
    captureState.pendingCapturePageUuid.value = null
    captureState.pendingCaptureBlockUuid.value = null
}

/** A photo taken via the camera viewfinder, awaiting save (bundled for parameter-count relief). */
private class CapturedPhoto(val file: PlatformImageFile, val pageUuid: String)

/** Where a successfully imported capture should land (bundled for parameter-count relief). */
private class CaptureTarget(val pageUuid: String, val captureBlockUuid: BlockUuid?, val navigateAfterImport: Boolean)

/**
 * Throwable (not just Exception) is caught below and the whole block runs in try/finally: an
 * uncaught Throwable on this scope (a plain rememberCoroutineScope() with no
 * CoroutineExceptionHandler) would otherwise kill the Android process and, even short of a
 * crash, skip the isCaptureImporting reset, leaving the save button stuck in its importing state.
 */
private suspend fun saveCapturedImage(
    deps: GraphContentDeps,
    viewModel: StelekitViewModel,
    inputs: GraphContentMainAreaInputs,
    captureState: CaptureState,
    photo: CapturedPhoto,
) {
    val target = CaptureTarget(
        pageUuid = photo.pageUuid,
        captureBlockUuid = captureState.pendingCaptureBlockUuid.value,
        navigateAfterImport = captureState.pendingCaptureNavigateAfterImport.value,
    )
    try {
        val graphPath = deps.graphManager.getActiveGraphInfo()?.path ?: return
        val result = importCapturedImage(inputs, photo, graphPath)
        applyCapturedImageResult(viewModel, inputs, result, target, graphPath)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        inputs.graphContentLogger.warn("Camera image import crashed: ${e.message}", e)
        viewModel.sendSnackbar("Image save failed — try again")
    } finally {
        captureState.isCaptureImporting.value = false
        clearPendingCapture(captureState)
    }
}

/**
 * ponytail: 20s timeout so a stalled save (blocked file IO, wedged DB write) can't leave
 * isCaptureImporting stuck true forever. Residual risk: if the timeout fires after the
 * sidecar/DB write but before the block-insert step, the cancelled import can leave an orphaned
 * ImageAnnotation with no visible block — same class of gap as any hard cancellation
 * mid-pipeline, not specific to this guard. Out of scope here; would need ImageImportService's
 * own step recovery hardened.
 */
private suspend fun importCapturedImage(
    inputs: GraphContentMainAreaInputs,
    photo: CapturedPhoto,
    graphPath: String,
): Either<DomainError, ImageAnnotation>? {
    val service = inputs.graphIoStack.imageImportService ?: return null
    val result = withImportTimeout {
        service.import(
            tempFile = photo.file,
            graphPath = graphPath,
            pageUuid = PageUuid(photo.pageUuid),
            source = ImageSource.CAMERA,
            insertToJournalPage = false,
        )
    }
    if (result == null) {
        inputs.graphContentLogger.warn("Camera image import timed out")
    }
    return result
}

private fun applyCapturedImageResult(
    viewModel: StelekitViewModel,
    inputs: GraphContentMainAreaInputs,
    result: Either<DomainError, ImageAnnotation>?,
    target: CaptureTarget,
    graphPath: String,
) {
    if (inputs.graphIoStack.imageImportService != null && result == null) {
        viewModel.sendSnackbar("Image save timed out — try again")
    }
    result?.onLeft { err ->
        inputs.graphContentLogger.warn("Camera image import failed: ${err.message}")
        viewModel.sendSnackbar(err.toUiMessage())
    }
    result?.onRight { annotation -> placeCapturedImage(inputs, viewModel, target, annotation, graphPath) }
}

private fun placeCapturedImage(
    inputs: GraphContentMainAreaInputs,
    viewModel: StelekitViewModel,
    target: CaptureTarget,
    annotation: ImageAnnotation,
    graphPath: String,
) {
    if (target.navigateAfterImport) {
        viewModel.navigateToAnnotationEditor(annotation.uuid, target.pageUuid)
    } else if (target.captureBlockUuid != null) {
        val relPath = annotation.filePath.removePrefix("$graphPath/")
        inputs.viewModelStack.blockStateManager.insertTextAtCursor(target.captureBlockUuid, markdownImageLink("", "../$relPath"))
    }
}
