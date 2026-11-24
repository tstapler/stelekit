// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.platform.SketchFilePort
import dev.stapler.stelekit.platform.SketchSaveOutcome
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportFailure
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportFile
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportFormat
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportRequest
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportScope
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportSource
import dev.stapler.stelekit.sketch.v2.runtime.export.PixelSize
import dev.stapler.stelekit.sketch.v2.runtime.export.SketchExportRunner
import dev.stapler.stelekit.sketch.v2.scene.underlay
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings.Export as Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One frame the Frame scope can crop to. */
data class FrameChoice(val id: ElementId, val label: String)

/** What the preview line shows for the current choices. */
sealed interface ExportPreview {
    /** Only a PNG has a pixel size; the other formats show nothing. */
    data object NotApplicable : ExportPreview

    data class Size(val widthPx: Long, val heightPx: Long) : ExportPreview {
        val text: String get() = Strings.previewSize(widthPx, heightPx)
    }

    /** The image would be over the side cap; [message] says so and the dialog offers "Use 1x". */
    data class TooLarge(val message: String, val scale: Int) : ExportPreview

    data object NothingToExport : ExportPreview
}

/**
 * The state of the export dialog as pure data (Story 5.2.3a, ux.md section 12): which formats and scopes are on offer and why one
 * is disabled, the flatten notice, and the preview or impossible-export message for the current choices.
 */
class ExportDialogModel(private val source: ExportSource, private val runner: SketchExportRunner) {
    val frames: List<FrameChoice> = source.scene.elementsInOrder.filterIsInstance<FrameElement>()
        .mapIndexed { i, frame -> FrameChoice(frame.id, Strings.frameName(frame.base.name, i + 1)) }

    /** The request the dialog opens with: the whole sketch, or the selection when something is selected. */
    fun initial(): ExportRequest = ExportRequest(scope = if (source.selection.isNotEmpty()) ExportScope.Selection else ExportScope.Whole)

    /** Why [format] cannot be chosen, or null. */
    fun disabledReason(format: ExportFormat): String? = when (format) {
        ExportFormat.Csv -> if (source.csv == null) Strings.CSV_DISABLED else null
        ExportFormat.Library -> if (source.library.isEmpty()) Strings.LIBRARY_EMPTY else null
        else -> null
    }

    /** Why [scope] cannot be chosen, or null. */
    fun disabledReason(scope: ExportScope): String? = when (scope) {
        ExportScope.Whole -> null
        ExportScope.Selection -> if (source.selection.isEmpty()) Strings.SELECTION_DISABLED else null
        ExportScope.Frame -> if (frames.isEmpty()) Strings.FRAME_DISABLED else null
    }

    /** True for formats that export a scope of the sheet; the library and CSV ignore scope, scale and style. */
    fun usesScope(format: ExportFormat): Boolean = format != ExportFormat.Library && format != ExportFormat.Csv

    /** Scale and background belong to the PNG alone; the background is also an SVG option. */
    fun usesScale(format: ExportFormat): Boolean = format == ExportFormat.Png

    fun usesBackground(format: ExportFormat): Boolean = format == ExportFormat.Png || format == ExportFormat.Svg

    fun usesStyle(format: ExportFormat): Boolean = format == ExportFormat.Png || format == ExportFormat.Svg

    /** The dimension annotations ride in every drawing format; the library and CSV have none. */
    fun usesDimensions(format: ExportFormat): Boolean = format == ExportFormat.Png || format == ExportFormat.Svg || format == ExportFormat.Excalidraw

    /** The "include photo underlay" option: only for a sheet that has an underlay, in the drawing formats (off unless chosen). */
    fun usesUnderlay(format: ExportFormat): Boolean = source.scene.underlay != null && usesDimensions(format)

    /** UX-58: shown only for the `.excalidraw` format and only when the sheet has more than one layer. */
    fun showsFlattenedNotice(request: ExportRequest): Boolean = request.format == ExportFormat.Excalidraw && source.scene.layers.size > 1

    /** [request] with any choice the sheet cannot honour put right: a disabled scope falls back to the whole sketch, a frame scope picks the first frame. */
    fun normalised(request: ExportRequest): ExportRequest {
        val scope = if (disabledReason(request.scope) != null) ExportScope.Whole else request.scope
        val frame = if (scope == ExportScope.Frame) request.frameId?.takeIf { id -> frames.any { it.id == id } } ?: frames.firstOrNull()?.id else null
        return request.copy(scope = scope, frameId = frame)
    }

    fun preview(request: ExportRequest): ExportPreview {
        val result = runner.measure(source, request) ?: return ExportPreview.NotApplicable
        return result.fold({ failure -> failurePreview(failure, request.scale) }, { it.toPreview() })
    }

    private fun PixelSize.toPreview() = ExportPreview.Size(widthPx, heightPx)

    private fun failurePreview(failure: ExportFailure, scale: Int): ExportPreview = when (failure) {
        is ExportFailure.TooLarge -> ExportPreview.TooLarge(tooLargeMessage(failure), scale)
        ExportFailure.NothingToExport -> ExportPreview.NothingToExport
        ExportFailure.EncodeFailed -> ExportPreview.NotApplicable
    }

    companion object {
        /** "This image would be 36,000 px wide. Choose 1x or export a frame." (UX-57); the side over the cap is named. */
        fun tooLargeMessage(failure: ExportFailure.TooLarge): String =
            when {
                failure.widthPx > failure.maxSidePx -> Strings.tooWide(failure.widthPx)
                failure.heightPx > failure.maxSidePx -> Strings.tooTall(failure.heightPx)
                else -> Strings.tooManyPixels(failure.widthPx, failure.heightPx)
            }
    }
}

/** Why the dialog stays open after Export. Each has its own one-tap fix. */
sealed interface ExportError {
    val message: String

    /** The size is over the cap; "Use 1x" sets 1x and clears this when the result fits. */
    data class TooLarge(override val message: String, val scale: Int) : ExportError

    data object EncodeFailed : ExportError {
        override val message: String get() = Strings.ENCODE_FAILED
    }

    data object NothingToExport : ExportError {
        override val message: String get() = Strings.NOTHING_TO_EXPORT
    }

    data object Unwritable : ExportError {
        override val message: String get() = Strings.UNWRITABLE
    }

    data object Blocked : ExportError {
        override val message: String get() = Strings.BLOCKED
    }

    data object Unexpected : ExportError {
        override val message: String get() = Strings.FAILED
    }
}

sealed interface ExportUiState {
    data object Closed : ExportUiState

    data class Open(
        val request: ExportRequest,
        val preview: ExportPreview,
        val error: ExportError? = null,
        val working: Boolean = false,
    ) : ExportUiState {
        /** Export is offered unless an export is running, nothing is left to export, or the size error is showing (Use 1x clears it). */
        val canExport: Boolean get() = !working && error !is ExportError.TooLarge && preview !is ExportPreview.NothingToExport
    }
}

/**
 * The export dialog's behaviour (Stories 5.2.3a and 5.2.3b), UI-free: option changes re-measure the PNG, Export renders and saves
 * off the main thread, and every failure keeps the dialog open with its fix (Use 1x, Retry, Choose another location, Try
 * again). Cancel and failure never post the success toast (UX-114). Owns its scope.
 */
class ExportDialogController(
    private val sourceOf: () -> ExportSource,
    private val runner: SketchExportRunner,
    private val files: SketchFilePort,
    private val postNotice: (SketchNotice) -> Unit,
    private val savedNotice: (SketchSaveOutcome.Saved) -> SketchNotice = ImportExportNotices::exportSaved,
    dispatcher: CoroutineDispatcher = PlatformDispatcher.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, _ -> failWith(ExportError.Unexpected) })
    private val mutableState = MutableStateFlow<ExportUiState>(ExportUiState.Closed)
    private var model: ExportDialogModel? = null
    private var job: Job? = null
    private var rendered: ExportFile? = null

    val state: StateFlow<ExportUiState> = mutableState.asStateFlow()

    /** The model of the open dialog, for the options the composable lists. */
    fun model(): ExportDialogModel? = model

    fun open() {
        val fresh = ExportDialogModel(sourceOf(), runner)
        model = fresh
        rendered = null
        val request = fresh.normalised(fresh.initial())
        mutableState.value = ExportUiState.Open(request, fresh.preview(request))
    }

    /** An option changed: the preview is measured again and a stale error goes. */
    fun update(request: ExportRequest) {
        val current = mutableState.value as? ExportUiState.Open ?: return
        val fresh = model ?: return
        val next = fresh.normalised(request)
        rendered = null
        mutableState.value = current.copy(request = next, preview = fresh.preview(next), error = null)
    }

    /** "Use 1x": sets 1x; Export is offered again when the result fits. */
    fun useOneX() {
        val current = mutableState.value as? ExportUiState.Open ?: return
        update(current.request.copy(scale = 1))
    }

    fun export() {
        val current = mutableState.value as? ExportUiState.Open ?: return
        if (!current.canExport || job?.isActive == true) return
        val tooLarge = current.preview as? ExportPreview.TooLarge
        if (tooLarge != null) {
            mutableState.value = current.copy(error = ExportError.TooLarge(tooLarge.message, tooLarge.scale))
            return
        }
        mutableState.value = current.copy(working = true, error = null)
        job = scope.launch { renderAndSave(current.request) }
    }

    /** "Retry", "Try again" and "Choose another location" all save again; the image is rendered again only after a render failure. */
    fun retry() = export()

    fun cancel() {
        job?.cancel()
        rendered = null
        mutableState.value = ExportUiState.Closed
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun renderAndSave(request: ExportRequest) {
        val file = rendered ?: runner.run(sourceOf(), request).fold({ failure -> return failWith(errorOf(failure, request)) }, { it })
        rendered = file
        save(file)
    }

    private suspend fun save(file: ExportFile) {
        val outcome = try {
            files.save(file.fileName, file.mimeType, file.bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failWith(ExportError.Unwritable)
        }
        val current = mutableState.value as? ExportUiState.Open ?: return
        when (outcome) {
            is SketchSaveOutcome.Saved -> {
                postNotice(withMissingNote(savedNotice(outcome), file.missingImages))
                rendered = null
                mutableState.value = ExportUiState.Closed
            }
            SketchSaveOutcome.Cancelled -> mutableState.value = current.copy(working = false)
            SketchSaveOutcome.Unwritable -> failWith(ExportError.Unwritable)
            SketchSaveOutcome.Blocked -> failWith(ExportError.Blocked)
        }
    }

    /** The export proceeded with placeholders: the footnote of ux.md section 12 goes into the toast's Details. */
    private fun withMissingNote(notice: SketchNotice, missing: Int): SketchNotice =
        if (missing == 0) notice else notice.copy(details = listOfNotNull(notice.details, Strings.missingImages(missing)).joinToString(NOTE_SEPARATOR))

    private fun errorOf(failure: ExportFailure, request: ExportRequest): ExportError = when (failure) {
        is ExportFailure.TooLarge -> ExportError.TooLarge(ExportDialogModel.tooLargeMessage(failure), request.scale)
        ExportFailure.EncodeFailed -> ExportError.EncodeFailed
        ExportFailure.NothingToExport -> ExportError.NothingToExport
    }

    private fun failWith(error: ExportError) {
        val current = mutableState.value as? ExportUiState.Open ?: return
        mutableState.value = current.copy(working = false, error = error)
    }

    private companion object {
        const val NOTE_SEPARATOR = ". "
    }
}
