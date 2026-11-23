// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.platform.CanvasFileDropHost
import dev.stapler.stelekit.platform.PickedImportFile
import dev.stapler.stelekit.platform.SketchFilePort
import dev.stapler.stelekit.platform.SketchSaveOutcome
import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.v2.commands.Applied
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.constraints.AutoConstrain
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.diagnostics.DiagnosticsPort
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.runtime.constraints.AutoConstrainResult
import dev.stapler.stelekit.sketch.v2.runtime.constraints.commitAutoConstrained
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportSource
import dev.stapler.stelekit.sketch.v2.runtime.export.PngExporter
import dev.stapler.stelekit.sketch.v2.runtime.export.SketchExportRunner
import dev.stapler.stelekit.sketch.v2.runtime.image.ImageStoreError
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.snap.GridSettings
import dev.stapler.stelekit.sketch.v2.snap.ObjectSnap
import dev.stapler.stelekit.sketch.v2.tools.AddElementCommand
import dev.stapler.stelekit.sketch.v2.tools.MoveSelectionCommand
import dev.stapler.stelekit.sketch.v2.tools.ToolCommand
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchHostStrings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The panels the selection menu's Align, Order and Transform items open over the canvas. */
enum class ExtraPanel { Align, Order, Transform }

/**
 * Everything the editor host mounts beyond the first host's surfaces (Task 6.2.0d), held away from [SketchEditorController]:
 * the dimension label editor, auto-constrain, grid and snap, the clipboard, the element library, Import and Export, and the
 * Align, Order and Transform panels. It reads and edits the document only through the controller (one undoable step per
 * edit), and solves only through the controller's [SolveRunner] gate. Owns its scope (never a composable's), so [close] ends it.
 */
class EditorExtras(
    private val controller: SketchEditorController,
    private val services: HostServices,
    environment: ModernPreviewEnvironment,
    /** The banner port Finger drawing raises its pen-detected and hint rows into; null shows none. */
    diagnostics: DiagnosticsPort? = null,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + controller.adoption + CoroutineExceptionHandler { _, t ->
            controller.notifier.toast(SketchHostStrings.Errors.EXTRA_FAILED_KEY, SketchHostStrings.Errors.EXTRA_FAILED)
            logUncaught(t)
        },
    )

    private fun logUncaught(t: Throwable) {
        if (t !is kotlinx.coroutines.CancellationException) controller.log.persistFailed(t::class.simpleName.orEmpty())
    }

    /** Notices from the importer and exporter (which run off the UI thread) reach the adoption-confined queue from here. */
    private val postNotice: (SketchNotice) -> Unit = { notice -> scope.launch { controller.notifier.post(notice) } }

    val tier: SolverTier get() = services.tier()

    /** True when constraint UI exists on this target; `Hidden` removes the tab, the entries and every aid. */
    val constraintsAvailable: Boolean get() = tier != SolverTier.Hidden

    /** Labels, freeing an arrow end and point editing of a line (the engine's own commands). */
    val engine = EngineSurfaces(controller, environment.metrics)

    /** Keyboard-only drawing (Crosshair mode) and the Esc of an armed picker; its Esc rows join [escRows]. */
    val access = EditorKeyboardAccess(controller)

    val dimension = DimensionEditor { controller.sheetKind == SheetKind.Technical }

    /** The on-canvas dimension pills of a technical sheet: whether they show and the size edit in progress. */
    val pills = DimensionPillsState()
    val autoConstrain = AutoConstrainState({ controller.sheetKind })

    var grid: Boolean by mutableStateOf(false)
        private set
    var snap: Boolean by mutableStateOf(false)
        private set
    var panel: ExtraPanel? by mutableStateOf(null)

    val clipboard = EditorClipboard(controller, services.limits) { text ->
        val (x, y) = viewportCentre()
        importer.paste(text, x, y)
    }
    val library = LibraryHost(controller, services)

    /** Finger drawing (UX-80, UX-121): the setting, the first-stylus flip and the one-finger hint; null where the setting or the banners are not wired. */
    val finger: FingerDrawingController? = services.platform.fingerDrawing?.let { settings ->
        val sequencer = controller.notifier.queue.sequencer
        if (diagnostics == null || sequencer == null) {
            null
        } else {
            FingerDrawingController(settings, diagnostics, sequencer, SketchEditorStrings.Banners) { controller.ui.fingerDrawing = it }
        }
    }

    /** The platform input seams: palm, system cancel, edge-pan strips and the web raw pointer source. */
    val input = PlatformInputHost(services.platform, controller.tools.adapter, controller.tools::onPointerActions, controller.notifier.queue.sequencer, finger)

    private val filePort: SketchFilePort = services.files ?: UnavailableFilePort

    val importer = ExcalidrawImportController(
        port = SheetPort(),
        files = filePort,
        images = ImportImageSink { bytes -> services.images?.store(bytes) ?: ImageStoreError.WriteFailed("").left() },
        storage = services.storage,
        postNotice = postNotice,
        ids = controller.ids,
        limits = services.limits,
        log = controller.log,
        commitContext = controller.adoption,
    )

    val exporter = ExportDialogController(
        sourceOf = ::exportSource,
        runner = SketchExportRunner(PngExporter(services.png, environment.resources, services.limits), DrawListBuilder(environment.metrics)),
        files = filePort,
        postNotice = postNotice,
    )

    /** Whether Import is offered, and why not: the entry is disabled with this text (UX-135). */
    val importDisabledReason: String?
        get() = ImportAvailability.reason(services.files, services.storage())

    val exportAvailable: Boolean get() = services.files != null

    val canCreateSheet: Boolean get() = services.createSheet != null

    /** The web canvas element's drop and paste handler, or null where the canvas has none. */
    val canvasDrop: CanvasFileDropHost? get() = services.platform.canvasDrop

    /** Text pasted on the canvas element (web): imported at the middle of the view like a keyboard paste. */
    fun pasteText(text: String) {
        val (x, y) = viewportCentre()
        importer.paste(text, x, y)
    }

    init {
        finger?.let { controller.ui.fingerDrawing = it.on }
        controller.constraints.onDimensionDraft = ::openDimensionDraft
        controller.extraKeys = ::onKey
        controller.interceptToolCommand = ::intercept
    }

    /** The Esc row of the dimension field, while its label editor or a dimension pill's size field is open. */
    fun escRows(): Set<EscRow> =
        (if (dimension.state == DimensionEditState.Closed && pills.edit == null) emptySet() else setOf(EscRow.TextOverlayOrDimensionField)) + access.escRows()

    fun close() {
        importer.close()
        exporter.close()
        scope.cancel()
    }

    // ---- dimension label editor

    private fun openDimensionDraft(draft: dev.stapler.stelekit.sketch.v2.constraints.Constraint): Boolean {
        dimension.openNew(controller.doc, draft, ConstraintValueText.of(draft, technical = controller.sheetKind == SheetKind.Technical).orEmpty())
        return dimension.state != DimensionEditState.Closed
    }

    /** Opens the label of dimension [id] (a glyph, a row or F2). */
    fun editDimension(id: ConstraintId) {
        dimension.openExisting(controller.doc, id)
    }

    fun submitDimension() {
        scope.launch { controller.runLabelEdit { runner -> dimension.submit(controller.doc, runner) } }
    }

    fun chooseDimension(kind: DimensionFailureKind) {
        scope.launch { controller.runLabelEdit { runner -> dimension.choose(kind, controller.doc, runner) } }
    }

    fun makeDimensionDriving() {
        scope.launch { controller.runLabelEdit { runner -> dimension.makeDriving(controller.doc, runner) } }
    }

    // ---- view toggles, snap and auto-constrain

    fun toggleGrid() {
        grid = !grid
        controller.notifier.toast(SketchHostStrings.View.GRID_KEY, if (grid) SketchHostStrings.View.GRID_ON else SketchHostStrings.View.GRID_OFF)
    }

    fun toggleSnap() {
        snap = !snap
        controller.notifier.toast(SketchHostStrings.View.SNAP_KEY, if (snap) SketchHostStrings.View.SNAP_ON else SketchHostStrings.View.SNAP_OFF)
    }

    /**
     * Runs on every finished gesture: a drawn shape with auto-constrain on goes through the solver with its inferred constraints,
     * a move snaps to objects and the grid. Returns the command to commit as usual, or null when it was committed here.
     */
    private fun intercept(command: ToolCommand): ToolCommand? = when (command) {
        is AddElementCommand -> if (autoConstrain.enabled && constraintsAvailable) autoConstrained(command) else command
        is MoveSelectionCommand -> snapped(command)
        else -> command
    }

    private fun autoConstrained(command: AddElementCommand): ToolCommand? {
        val element = if (controller.activeLayer == dev.stapler.stelekit.sketch.v2.scene.LayerId.Default) {
            command.element
        } else {
            command.element.withBase(command.element.base.copy(layerId = controller.activeLayer))
        }
        val plan = AutoConstrain.plan(controller.doc, element, autoConstrain.optionsForNextShape(ctrlHeld = false)) { ConstraintId(controller.ids.next().value) }
        if (plan.constraints.isEmpty()) return command
        scope.launch {
            var result: AutoConstrainResult? = null
            controller.runLabelEdit { runner ->
                runner.commitAutoConstrained(controller.doc, plan).also { result = it }.let { (it as? AutoConstrainResult.WithConstraints)?.done ?: (it as? AutoConstrainResult.Plain)?.done }
            }
            result?.let { dev.stapler.stelekit.ui.sketch.v2.ConstraintToasts.post(controller.notices, it, controller.notifier.now()) }
        }
        return null
    }

    private fun snapped(command: MoveSelectionCommand): ToolCommand {
        if (!snap && !grid) return command
        val moving = command.ids.toSet()
        val settings = GridSettings.diagram(grid)
        val scene = controller.doc.scene
        val zoom = controller.ui.viewport.zoom
        if (snap) {
            val out = ObjectSnap.snapMove(scene, controller.spatialIndex, moving, command.dx, command.dy, zoom, grid = settings.takeIf { grid })
            return command.copy(dx = out.dx, dy = out.dy)
        }
        val base = SceneBounds.of(command.ids.mapNotNull { scene[it] }) ?: return command
        return command.copy(dx = settings.snap(base.minX + command.dx) - base.minX, dy = settings.snap(base.minY + command.dy) - base.minY)
    }

    private fun onKey(action: KeyAction): Boolean = when (action) {
        KeyAction.Copy -> true.also { clipboard.copy() }
        KeyAction.Cut -> true.also { clipboard.cut() }
        KeyAction.Paste -> true.also { clipboard.paste() }
        KeyAction.ToggleGrid -> true.also { toggleGrid() }
        KeyAction.ToggleSnap -> true.also { toggleSnap() }
        else -> false
    }

    // ---- panels

    /** The selection menu's Align, Order, Transform and Bind end: each opens the panel that runs it. */
    fun open(action: SelectionAction) {
        panel = when (action) {
            SelectionAction.Align -> ExtraPanel.Align
            SelectionAction.Order -> ExtraPanel.Order
            SelectionAction.Transform, SelectionAction.BindEnd -> ExtraPanel.Transform
            else -> return
        }
    }

    // ---- export

    private fun exportSource() = ExportSource(
        scene = controller.doc.scene,
        selection = controller.ui.selection.mapTo(LinkedHashSet(), ::ElementId),
        images = services.imageSource,
        library = library.items,
    )

    private fun viewportCentre(): Pair<Double, Double> {
        val size = controller.view.canvasSize
        val centre = controller.ui.viewport.toScene(ScreenPx(size.x / HALF, size.y / HALF))
        return centre.x.value to centre.y.value
    }

    private inner class SheetPort : ImportSheetPort {
        override fun document() = controller.doc

        override fun viewportCentre(): Pair<Double, Double> = this@EditorExtras.viewportCentre()

        override fun commit(command: Command): Either<CommandError, Applied> = controller.commitNow(command)

        override fun undo(): Boolean = controller.canUndo.also { if (it) controller.undo() }

        override fun select(ids: Set<ElementId>) = controller.tools.select(ids.toList())

        override suspend fun createSheet(document: dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2): Either<String, Unit> = services.newSheet(document)
    }

    private companion object {
        const val HALF = 2.0
    }
}

/** Stands in where the platform wired no file seam: it cannot pick and every save is refused. */
private object UnavailableFilePort : SketchFilePort {
    override val canPick: Boolean = false

    override suspend fun pickImportFile(title: String, extensions: List<String>): PickedImportFile? = null

    override suspend fun save(suggestedName: String, mimeType: String, bytes: ByteArray): SketchSaveOutcome = SketchSaveOutcome.Unwritable
}
