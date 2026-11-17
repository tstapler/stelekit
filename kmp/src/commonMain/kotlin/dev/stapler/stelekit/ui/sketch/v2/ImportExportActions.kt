// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.platform.DroppedCanvasFile
import dev.stapler.stelekit.platform.SketchFilePort
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.projectbridge.isAvailable
import dev.stapler.stelekit.sketch.excalidraw.ImportError
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.clipboard.ExcalidrawClipboard
import dev.stapler.stelekit.sketch.v2.clipboard.ExcalidrawPaste
import dev.stapler.stelekit.sketch.v2.commands.Applied
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.runtime.image.ImageStoreError
import dev.stapler.stelekit.sketch.v2.runtime.image.StoredImage
import dev.stapler.stelekit.sketch.v2.runtime.importing.ExcalidrawImportRunner
import dev.stapler.stelekit.sketch.v2.runtime.importing.OverCap
import dev.stapler.stelekit.sketch.v2.runtime.importing.OverCapChoice
import dev.stapler.stelekit.sketch.v2.runtime.importing.PreparedImport
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.ui.sketch.SketchReasonText
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings.Codes
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings.Import as Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** What the importer needs from the sheet being edited. The editor host implements it over its history and saver. */
interface ImportSheetPort {
    fun document(): SketchDocumentV2

    /** The viewport centre in scene units: where an import lands. */
    fun viewportCentre(): Pair<Double, Double>

    /** Runs [command] as ONE transaction (one history entry); the result is the document the host now holds. */
    fun commit(command: Command): Either<CommandError, Applied>

    /** Undoes the newest history entry; true when it did. */
    fun undo(): Boolean

    fun select(ids: Set<ElementId>)

    /** Adds a sheet holding [document] to the project beside the current one (Story 2.2.4's new-sheet path). Left is a reason token. */
    suspend fun createSheet(document: SketchDocumentV2): Either<String, Unit>
}

/** Stores one embedded picture once under the graph's assets (`ImageAssetStore.store`). */
fun interface ImportImageSink {
    fun store(bytes: ByteArray): Either<ImageStoreError, StoredImage>
}

/** Where the dialog is. One value at a time; [Idle] closes every dialog. */
sealed interface ImportUiState {
    data object Idle : ImportUiState

    /** The platform cannot hold new-editor sketches, or cannot pick a file: [reason] says why and nothing was created (UX-135). */
    data class Unavailable(val reason: String) : ImportUiState

    data class Reading(val name: String, val sizeBytes: Long) : ImportUiState

    /** The file is read; the user chooses before anything is added. [overCap] is set when it does not fit; [newerSchema] adds the notice. */
    data class Confirm(val name: String, val overCap: OverCap?, val newerSchema: Boolean, val overCapText: String?) : ImportUiState

    data class Summary(val lines: List<String>, val details: String, val canUndo: Boolean) : ImportUiState

    /** A refusal or failure with its message and, where one exists, its reason code. [canChooseAnother] is false for a paste. */
    data class Refused(val message: String, val code: String?, val canChooseAnother: Boolean) : ImportUiState

    data class Notice(val message: String) : ImportUiState
}

/**
 * The import flow (Stories 5.1.5a to c), UI-free so a test drives it without a screen: pick or receive a file, read it off the
 * main thread with Cancel, ask when it is over the sheet's cap or from a newer Excalidraw, add it as ONE undoable command, and
 * summarise with Copy details and Undo import. Nothing touches the sheet until the final commit, so a cancel, a refusal or a
 * process death before it leaves the sheet unchanged (UX-54). Owns its scope (a long-lived class never takes a composable's).
 */
class ExcalidrawImportController(
    private val port: ImportSheetPort,
    private val files: SketchFilePort,
    private val images: ImportImageSink,
    private val storage: () -> V2Storage,
    private val postNotice: (SketchNotice) -> Unit,
    private val ids: ElementIdSource,
    private val log: SketchLog = SketchLog(),
    private val limits: SceneLimits = SceneLimits.Default,
    dispatcher: CoroutineDispatcher = PlatformDispatcher.Default,
    /** Where the sheet is changed: the host passes its main dispatcher, since the read and parse run on [dispatcher]. */
    private val commitContext: CoroutineContext = EmptyCoroutineContext,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, _ -> fail(Codes.UNEXPECTED, Strings.FAILED) },
    )
    private val runner = ExcalidrawImportRunner(limits)
    private val applier = ImportApplier(port, images, commitContext)
    private val mutableState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    private var job: Job? = null
    private var pending: PreparedImport? = null
    private var finished: ImportUiState.Summary? = null
    private var finishedDocument: SketchDocumentV2? = null

    val state: StateFlow<ImportUiState> = mutableState.asStateFlow()

    /** Releases the scope; call when the editor leaves the screen. */
    fun close() {
        scope.cancel()
    }

    /** The overflow item and the empty-state Import button: pick a file, then read it. */
    fun chooseFile() {
        if (busy || blockedByPlatform()) return
        if (!files.canPick) {
            mutableState.value = ImportUiState.Unavailable(Strings.NO_PICKER)
            return
        }
        job = scope.launch {
            val picked = files.pickImportFile(Strings.MENU_ITEM, FILE_EXTENSIONS) ?: return@launch
            read(picked.name, picked.source)
        }
    }

    /** A file dropped on the canvas (desktop, web). */
    fun importDropped(file: DroppedCanvasFile) {
        if (busy || blockedByPlatform()) return
        begin(file.name, file.source)
        job = scope.launch { read(file.name, file.source) }
    }

    /**
     * Clipboard text pasted on the canvas at ([cursorX], [cursorY]). Text that is not Excalidraw does nothing at all; Excalidraw
     * text on a platform without file storage shows the reason and creates nothing (UX-135).
     */
    fun paste(text: String, cursorX: Double, cursorY: Double) {
        if (busy) return
        job = scope.launch {
            when (val paste = ExcalidrawClipboard.read(text, cursorX, cursorY, ids, limits)) {
                ExcalidrawPaste.NotExcalidraw -> Unit
                is ExcalidrawPaste.Refused -> if (blockedByPlatform()) Unit else refuse(paste.error, canChooseAnother = false)
                is ExcalidrawPaste.Ready -> if (!blockedByPlatform()) commitPaste(paste)
            }
        }
    }

    /** The user's answer to the over-cap dialog. */
    fun chooseOverCap(choice: OverCapChoice) {
        val prepared = pending ?: return
        if (choice == OverCapChoice.Cancel) return cancel()
        job = scope.launch { commit(prepared, choice) }
    }

    /** "Import" on the newer-Excalidraw notice (a file that fits). */
    fun confirmNewer() {
        val prepared = pending ?: return
        job = scope.launch { commit(prepared, null) }
    }

    /** Cancel on the progress dialog, the over-cap dialog or the newer-schema notice: nothing is changed. */
    fun cancel() {
        job?.cancel()
        pending = null
        mutableState.value = ImportUiState.Notice(Strings.CANCELLED)
    }

    /** Closes whatever dialog is open (Done, Close). */
    fun dismiss() {
        pending = null
        mutableState.value = ImportUiState.Idle
    }

    /** True while the sheet is exactly as the import left it, so undoing it cannot take another edit with it. */
    fun canUndoImport(): Boolean = finishedDocument?.let { it === port.document() } == true

    /** "Undo import": undoes the single `ImportScene` command; hidden once another edit followed (no out-of-order undo). */
    fun undoImport() {
        if (!canUndoImport() || !port.undo()) return
        finishedDocument = null
        finished = null
        port.select(emptySet())
        postNotice(ImportExportNotices.importUndone())
        mutableState.value = ImportUiState.Idle
    }

    /** Opens the last summary again, from the notice that outlives the dialog. */
    fun reopenSummary() {
        val last = finished ?: return
        mutableState.value = last.copy(canUndo = canUndoImport())
    }

    /** The text of "Copy details". */
    fun detailsText(): String = finished?.details.orEmpty()

    // ---- the flow

    private val busy: Boolean get() = job?.isActive == true

    /** Sets [ImportUiState.Unavailable] and returns true where a new-editor sketch cannot be held or no file can be picked. */
    private fun blockedByPlatform(): Boolean {
        val where = storage()
        if (!where.isAvailable) {
            mutableState.value = ImportUiState.Unavailable(SketchReasonText.v2StorageUnavailable(where))
            return true
        }
        return false
    }

    /** Shows the progress dialog and forgets the last import; set before the read starts so Cancel works at once. */
    private fun begin(name: String, source: ImportSource) {
        finished = null
        finishedDocument = null
        mutableState.value = ImportUiState.Reading(name, source.size)
    }

    private suspend fun read(name: String, source: ImportSource) {
        begin(name, source)
        val prepared = try {
            runner.prepare(source, port.document().scene.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return fail(Codes.UNEXPECTED, Strings.FAILED)
        }
        prepared.fold(
            { error -> refuse(error, canChooseAnother = true) },
            { ready ->
                pending = ready
                if (ready.overCap != null || ready.newerSchema) {
                    mutableState.value = ImportUiState.Confirm(name, ready.overCap, ready.newerSchema, ready.overCap?.let(ImportMessages::overCap))
                } else {
                    commit(ready, null)
                }
            },
        )
    }

    private suspend fun commit(prepared: PreparedImport, choice: OverCapChoice?) {
        val (centreX, centreY) = port.viewportCentre()
        val plan = prepared.plan(choice, centreX, centreY) ?: return cancel()
        if (plan.elements.isEmpty() && prepared.overCap != null) return fail(Codes.CAPACITY_EXCEEDED, Strings.CAP_REACHED)
        currentCoroutineContext().ensureActive()
        val added = when (val applied = applier.apply(plan)) {
            is ImportApplied.Failed -> return fail(applied.code, applied.message)
            is ImportApplied.Added -> applied
        }
        finishedDocument = added.document
        val lines = ImportSummaryText.lines(prepared.scene, plan.elements.size, plan.overLimit)
        val summary = ImportUiState.Summary(lines, ImportSummaryText.details(prepared.scene), canUndo = finishedDocument != null)
        finished = summary
        pending = null
        log.importSummary(plan.elements.size, prepared.scene.report.total - plan.elements.size)
        postNotice(ImportExportNotices.importFinished(lines.first()))
        mutableState.value = summary
    }

    private suspend fun commitPaste(paste: ExcalidrawPaste.Ready) {
        when (val applied = applier.applyPaste(paste)) {
            is ImportApplied.Failed -> fail(applied.code, applied.message)
            is ImportApplied.Added -> {
                finishedDocument = applied.document
                log.importSummary(paste.pastedIds.size, paste.report.total - paste.report.imported)
            }
        }
    }

    private fun refuse(error: ImportError, canChooseAnother: Boolean) {
        val code = error.reason.name
        log.importFailed(code)
        pending = null
        mutableState.value = ImportUiState.Refused(ImportMessages.refusal(error.reason, limits), code, canChooseAnother)
    }

    private fun fail(code: String, message: String) {
        log.importFailed(code)
        pending = null
        mutableState.value = ImportUiState.Refused(message, code, canChooseAnother = false)
    }

    private companion object {
        val FILE_EXTENSIONS = listOf("excalidraw", "json")
    }
}
