// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.platform.SketchFilePort
import dev.stapler.stelekit.platform.SketchSaveOutcome
import dev.stapler.stelekit.sketch.v2.export.SheetPlan
import dev.stapler.stelekit.sketch.v2.export.SheetPlanner
import dev.stapler.stelekit.sketch.v2.export.SheetSetup
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

    /**
     * The sheet on paper: the page [plan] (margins, the drawing's place, the pages it needs), the PNG's [pixels] when the format is a
     * PNG, and [tooLarge] when that PNG is over the size cap. [tiled] is the request's choice, which decides whether too many pages blocks Export.
     */
    data class Page(val plan: SheetPlan, val pixels: PixelSize?, val tooLarge: String?, val tiled: Boolean) : ExportPreview {
        /** True when Export must be refused: a tiled export needing more pages than the writer takes. */
        val blocked: Boolean get() = tiled && plan.tooManyPages

        val pixelText: String? get() = pixels?.let { Strings.previewSize(it.widthPx, it.heightPx) }
    }
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

    fun usesBackground(format: ExportFormat): Boolean = format == ExportFormat.Png || format == ExportFormat.Svg || format == ExportFormat.Pdf

    fun usesStyle(format: ExportFormat): Boolean = format == ExportFormat.Png || format == ExportFormat.Svg || format == ExportFormat.Pdf

    /** The dimension annotations ride in every drawing format; the library and CSV have none. */
    fun usesDimensions(format: ExportFormat): Boolean = format == ExportFormat.Png || format == ExportFormat.Svg || format == ExportFormat.Excalidraw || format == ExportFormat.Pdf

    /** The "include photo underlay" option: only for a sheet that has an underlay, in the picture formats (off unless chosen). The PDF has no embedded images. */
    fun usesUnderlay(format: ExportFormat): Boolean = source.scene.underlay != null && usesDimensions(format) && format != ExportFormat.Pdf

    /** The page section (paper, drawing scale, furniture): an SVG or PNG can be printed on a page, a PDF always is. */
    fun usesPage(format: ExportFormat): Boolean = format == ExportFormat.Png || format == ExportFormat.Svg || format == ExportFormat.Pdf

    /** True when the page section is a choice (SVG, PNG); false when the PDF always prints on a page. */
    fun pageIsOptional(format: ExportFormat): Boolean = format != ExportFormat.Pdf

    /** Whether the length notation is metric, for the scale list and the scale bar. */
    val metric: Boolean get() = source.metric

    /** Whether the sheet shows a tiling choice: only a PDF is written as several pages. */
    fun usesTiling(request: ExportRequest): Boolean = request.format == ExportFormat.Pdf && runner.plan(source, request)?.fits == false

    /** [request] with the page layout turned on (the sheet's own setup, else a suggestion) or off. */
    fun withPage(request: ExportRequest, on: Boolean): ExportRequest =
        if (on) request.copy(sheet = request.sheet ?: runner.suggestedSetup(source, request)) else request.copy(sheet = null, tiled = false)

    /** [request] with the largest standard scale that fits one page, or unchanged when none does. */
    fun fitted(request: ExportRequest): ExportRequest {
        val setup = request.sheet ?: return request
        val plan = runner.plan(source, request) ?: return request
        val best = SheetPlanner.bestFit(setup, plan.content, source.metric) ?: return request
        return request.copy(sheet = setup.copy(scale = best), tiled = false)
    }

    /** UX-58: shown only for the `.excalidraw` format and only when the sheet has more than one layer. */
    fun showsFlattenedNotice(request: ExportRequest): Boolean = request.format == ExportFormat.Excalidraw && source.scene.layers.size > 1

    /** [request] with any choice the sheet cannot honour put right: a disabled scope falls back to the whole sketch, a frame scope picks the first frame. */
    fun normalised(request: ExportRequest): ExportRequest {
        val scope = if (disabledReason(request.scope) != null) ExportScope.Whole else request.scope
        val frame = if (scope == ExportScope.Frame) request.frameId?.takeIf { id -> frames.any { it.id == id } } ?: frames.firstOrNull()?.id else null
        val base = request.copy(scope = scope, frameId = frame)
        val withSheet = if (base.format == ExportFormat.Pdf && base.sheet == null) base.copy(sheet = runner.suggestedSetup(source, base)) else base
        val tiled = withSheet.tiled && withSheet.format == ExportFormat.Pdf && runner.plan(source, withSheet)?.fits == false
        return if (tiled == withSheet.tiled) withSheet else withSheet.copy(tiled = tiled)
    }

    fun preview(request: ExportRequest): ExportPreview {
        if (request.onPaper) return pagePreview(request)
        val result = runner.measure(source, request) ?: return ExportPreview.NotApplicable
        return result.fold({ failure -> failurePreview(failure, request.scale) }, { ExportPreview.Size(it.widthPx, it.heightPx) })
    }

    private fun pagePreview(request: ExportRequest): ExportPreview {
        val plan = runner.plan(source, request) ?: return ExportPreview.NothingToExport
        val measured = runner.measure(source, request)
        val pixels = measured?.getOrNull()
        val tooLarge = (measured?.leftOrNull() as? ExportFailure.TooLarge)?.let(::tooLargeMessage)
        return ExportPreview.Page(plan, pixels, tooLarge, request.tiled)
    }

    private fun failurePreview(failure: ExportFailure, scale: Int): ExportPreview = when (failure) {
        is ExportFailure.TooLarge -> ExportPreview.TooLarge(tooLargeMessage(failure), scale)
        ExportFailure.NothingToExport -> ExportPreview.NothingToExport
        ExportFailure.EncodeFailed -> ExportPreview.NotApplicable
        is ExportFailure.TooManyPages -> ExportPreview.NotApplicable
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

/** The verdict of the fit check: [fits] on one page, the [text] that says so, and the largest standard scale that would fit when it does not. */
data class FitCheck(val fits: Boolean, val text: String, val suggestion: dev.stapler.stelekit.sketch.v2.export.DrawingScale?)

/** How the drawing sits on the page, in words: the fit check the page preview shows. */
fun ExportDialogModel.fitCheck(plan: SheetPlan): FitCheck {
    val setup = plan.setup
    val imperial = setup.paper.imperial
    val need = "${SheetPlanner.lengthText(plan.scaledWidth, imperial)} x ${SheetPlanner.lengthText(plan.scaledHeight, imperial)}"
    val room = "${SheetPlanner.lengthText(plan.area.width, imperial)} x ${SheetPlanner.lengthText(plan.area.height, imperial)}"
    val scale = setup.scale.label
    val paper = Strings.paperName(setup.paper.label, setup.orientation)
    if (plan.fits) return FitCheck(true, Strings.fits(scale, paper, need, room), null)
    val better = SheetPlanner.bestFit(setup, plan.content, metric)
    return FitCheck(false, Strings.overflow(scale, paper, need, room, plan.columns, plan.rows), better)
}


/** Why the dialog stays open after Export. Each has its own one-tap fix. */
sealed interface ExportError {
    val message: String

    /** The size is over the cap; "Use 1x" sets 1x and clears this when the result fits. */
    data class TooLarge(override val message: String, val scale: Int) : ExportError

    data object EncodeFailed : ExportError {
        override val message: String get() = Strings.ENCODE_FAILED
    }

    /** A tiled PDF would need more pages than the writer takes; a smaller scale or a bigger paper fixes it. */
    data class TooManyPages(val pages: Int, val budget: Int) : ExportError {
        override val message: String get() = Strings.tooManyPages(pages, budget)
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
        val canExport: Boolean get() = !working && error !is ExportError.TooLarge && preview !is ExportPreview.NothingToExport &&
            (preview as? ExportPreview.Page)?.blocked != true
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
    private val rememberSheet: (SheetSetup) -> Unit = {},
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
        update(current.request.copy(scale = 1, dpi = ExportRequest.DPIS.first()))
    }

    fun export() {
        val current = mutableState.value as? ExportUiState.Open ?: return
        if (!current.canExport || job?.isActive == true) return
        val tooLarge = current.preview as? ExportPreview.TooLarge
        if (tooLarge != null) {
            mutableState.value = current.copy(error = ExportError.TooLarge(tooLarge.message, tooLarge.scale))
            return
        }
        (current.preview as? ExportPreview.Page)?.tooLarge?.let { message ->
            mutableState.value = current.copy(error = ExportError.TooLarge(message, 1))
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
                current.request.sheet?.let(rememberSheet)
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
        is ExportFailure.TooManyPages -> ExportError.TooManyPages(failure.pages, failure.budget)
    }

    private fun failWith(error: ExportError) {
        val current = mutableState.value as? ExportUiState.Open ?: return
        mutableState.value = current.copy(working = false, error = error)
    }

    private companion object {
        const val NOTE_SEPARATOR = ". "
    }
}
