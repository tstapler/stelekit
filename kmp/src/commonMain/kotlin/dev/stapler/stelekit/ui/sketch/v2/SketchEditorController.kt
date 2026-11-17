// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import arrow.core.flatMap
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.Committed
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveOutcome
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRejection
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveState
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaver
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchErrorState
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.tools.AddElementCommand
import dev.stapler.stelekit.sketch.v2.tools.AddFrameCommand
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.sketch.v2.tools.InputEvent
import dev.stapler.stelekit.sketch.v2.tools.InputType
import dev.stapler.stelekit.sketch.v2.tools.SelectTool
import dev.stapler.stelekit.sketch.v2.tools.SelectionAware
import dev.stapler.stelekit.sketch.v2.tools.TextTool
import dev.stapler.stelekit.sketch.v2.tools.Tool
import dev.stapler.stelekit.sketch.v2.tools.ToolCommand
import dev.stapler.stelekit.sketch.v2.tools.ToolContext
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.tools.ToolNotice
import dev.stapler.stelekit.sketch.v2.tools.ToolPreview
import dev.stapler.stelekit.sketch.v2.tools.ToolRegistry
import dev.stapler.stelekit.sketch.v2.tools.toCommand
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchErrorStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/** What the host may open over the canvas on the controller's say (a key or a menu): one at a time. */
enum class EditorOverlay { Shortcuts, GoToElement, ConstrainMenu }

/** The seams the controller talks to; every one has a default so a test names only what it checks. */
class EditorPorts(
    val tools: ToolRegistry,
    /** Persists every committed document; null edits in memory only (a read-only or throwaway session). */
    val saver: SheetSaver? = null,
    val solver: SolveRunner? = null,
    val ids: ElementIdSource,
    val limits: SceneLimits = SceneLimits.Default,
    val log: SketchLog = SketchLog(),
    val clockMillis: () -> Long = { 0L },
    val keymap: KeymapEnv = KeymapEnv(isMac = false, isWeb = false),
    val touchPresent: Boolean = false,
)

/**
 * The state holder of one open v2 sheet (Story 6.2.0a): it owns the [SketchDocumentV2] and its [HistoryV2], the active tool, the
 * selection and view (in [ui], the state the shell reads and writes), the saver and the solve runner, and it is the one place a
 * finished gesture, a key or a panel command becomes an undoable edit. Platform-free and Compose-free: it takes no composition
 * scope; it owns [scope] with a [CoroutineExceptionHandler], so an `OutOfMemoryError` in a solve never reaches the platform's
 * uncaught-exception handler (which kills an Android process). Call [close] when the editor leaves.
 *
 * Edits run in ONE [Transaction] per gesture. A sheet with constraints, and any command that adds one, runs through
 * [SolveRunner] only (the controller never calls the solver); the edit and the solve are one history step. Other edits apply on
 * the calling thread. Edits never overlap: while a solve is in flight later edits wait behind it.
 */
class SketchEditorController(
    initial: SketchDocumentV2,
    private val ports: EditorPorts,
    context: CoroutineContext = Dispatchers.Default,
    val ui: SketchEditorState = SketchEditorState(),
    /** When true the sheet is shown but no edit applies (the router said read-only or another tab holds the lease). */
    val readOnly: Boolean = false,
) {
    private val scope = CoroutineScope(context + SupervisorJob() + CoroutineExceptionHandler { _, t -> onUncaught(t) })
    private val runner: SolveRunner = ports.solver ?: SolveRunner(ports.limits, context = context, log = ports.log)
    private val gate = Mutex()
    private val pending = MutableStateFlow(0)
    private val index = SpatialIndex()

    var history: HistoryV2 by mutableStateOf(HistoryV2.of(initial, ports.limits))
        private set

    /** Bumped on every document change; a draw list or an index is rebuilt when it moves. */
    var revision: Long by mutableStateOf(0L)
        private set

    /** The shape being drawn over the canvas; null when no gesture runs. */
    var preview: ToolPreview? by mutableStateOf(null)
        private set

    var activeLayer: LayerId by mutableStateOf(LayerId.Default)

    /** The style new shapes take; the Style panel's defaults row writes it. */
    var defaultStyle: ElementStyle by mutableStateOf(ElementStyle())

    /** What the last solve said about the constraints, for the Constraints tab. */
    var analysis: ConstraintAnalysis by mutableStateOf(ConstraintAnalysis.Unknown)
        private set

    var overlay: EditorOverlay? by mutableStateOf(null)

    /** The text edit open on the canvas (the Text tool's editing state), or null. */
    var textSession: dev.stapler.stelekit.sketch.v2.tools.TextEditSession? by mutableStateOf(null)
        private set

    /** Where a secondary click or a long press asked for a context menu, in scene space; the host shows and clears it. */
    var contextMenuAt: ScenePoint? by mutableStateOf(null)

    /** The canvas size in pixels; the host reports it so fit and page-pan know the view. */
    var canvasSize: ScreenPx = ScreenPx(0.0, 0.0)

    val doc: SketchDocumentV2 get() = history.present
    val canUndo: Boolean get() = history.canUndo && !readOnly
    val canRedo: Boolean get() = history.canRedo && !readOnly
    val saveState: StateFlow<SaveState> = ports.saver?.state ?: MutableStateFlow(SaveState.Saved)
    val solveState: StateFlow<SolveState> = runner.state
    val adapter = SketchPointerAdapter(ui.viewport, ui.fingerDrawing)
    val notices = SketchNoticeQueue(ports.limits, log = ports.log)

    /** The spatial index of the current scene, shared with the canvas for culling and with the tools for hit tests. */
    val spatialIndex: SpatialIndex get() = index

    private var tool: Tool = ports.tools.create(ToolId.Select) ?: error("the tool registry has no Select tool")
    private var overrideTool: Tool? = null

    init {
        index.rebuild(initial.scene)
        adapter.isEmptyCanvas = { px -> context().hitTester.hit(ui.viewport.toScene(px), ui.viewport.zoom) == null }
        configureAdapter()
        if (initial.constraints.isNotEmpty()) analyze()
    }

    // ---- tools and pointer ---------------------------------------------------------------------------------------

    /** Feeds what [SketchPointerAdapter] decided to the active tool, the view and the context menu. */
    fun onPointerActions(actions: List<PointerAction>) {
        for (action in actions) {
            when (action) {
                is PointerAction.ToolInput -> if (!readOnly) feed(action)
                is PointerAction.ViewChange -> ui.viewport = action.viewport
                is PointerAction.ContextMenu -> contextMenuAt = action.at
            }
        }
    }

    /** One raw pointer sample from the canvas (the host's `sketchPointerInput` calls this); the shell's view and tool state apply first. */
    fun onPointer(sample: RawPointer) {
        syncAdapter()
        onPointerActions(adapter.onPointer(sample))
    }

    fun onWheel(wheel: WheelInput) {
        syncAdapter()
        onPointerActions(adapter.onWheel(wheel))
    }

    /** Picks [id] from the strip, a key or the overflow menu. */
    fun chooseTool(id: ToolId) {
        ui.chooseTool(id)
        syncTool()
    }

    private fun syncAdapter() {
        if (adapter.viewport != ui.viewport) adapter.setViewport(ui.viewport)
        adapter.fingerDrawing = ui.fingerDrawing
        syncTool()
    }

    /** Makes [tool] follow `ui.activeTool` (the strip writes it directly) and the adapter follow the tool. */
    private fun syncTool() {
        if (tool.id != ui.activeTool) {
            tool = freshTool(ui.activeTool)
            preview = null
            textSession = null
            ports.log.toolActivated(ui.activeTool.name)
        }
        configureAdapter()
    }

    private fun freshTool(id: ToolId): Tool =
        if (id == ToolId.Select) SelectTool(ui.selection.map(::ElementId), ports.touchPresent) else ports.tools.create(id) ?: tool

    private fun configureAdapter() {
        val active = ui.activeTool
        adapter.handTool = active == ToolId.Hand
        adapter.selectTool = active == ToolId.Select
        adapter.touchTapsReachTool = active == ToolId.Select || active == ToolId.Eraser
        adapter.drawingTool = active != ToolId.Select && active != ToolId.Hand
    }

    private fun feed(input: PointerAction.ToolInput) {
        val override = input.toolOverride
        val running = if (override != null) overrideTool ?: ports.tools.create(override) else tool
        running ?: return
        val result = running.onEvent(input.event, context())
        if (override != null) {
            overrideTool = if (input.event.type == InputType.Up || input.event.type == InputType.Cancel) null else result.next ?: running
        } else {
            result.next?.let { tool = it }
            preview = result.preview
            readSelection()
            textSession = (tool as? TextTool.Editing)?.session
        }
        result.notice?.let(::postToolNotice)
        result.command?.let { commitToolCommand(it) }
    }

    private fun readSelection() {
        (tool as? SelectionAware)?.let { ui.selection = it.selection.mapTo(LinkedHashSet()) { id -> id.value } }
    }

    private fun commitToolCommand(command: ToolCommand) {
        val creation = command as? AddElementCommand
        val placed = if (creation != null && activeLayer != LayerId.Default) {
            AddElementCommand(creation.element.withBase(creation.element.base.copy(layerId = activeLayer)))
        } else {
            command
        }
        placed.toCommand().fold({ refuse(it) }, { submit(it) })
        if (creation != null || command is AddFrameCommand) afterCreation(creation)
    }

    /** A shape was committed: the tool returns to Select unless tool lock is on (UX-04) and the new element is selected. */
    private fun afterCreation(created: AddElementCommand?) {
        val keep = ui.toolLock
        ui.onShapeCommitted()
        if (!keep) {
            ui.selection = created?.let { setOf(it.element.id.value) } ?: emptySet()
            tool = freshTool(ui.activeTool)
            preview = null
            configureAdapter()
        }
    }

    /** Commits the open text edit (the overlay's Done, a tap outside, Enter-less blur). */
    fun commitText() {
        val editing = tool as? TextTool.Editing ?: return
        val result = editing.commit(context())
        result.next?.let { tool = it }
        textSession = (tool as? TextTool.Editing)?.session
        result.command?.let { commitToolCommand(it) }
    }

    private fun context() = ToolContext(doc.scene, ui.viewport.zoom, defaultStyle, ports.ids, index)

    private fun postToolNotice(notice: ToolNotice) {
        val (key, text) = when (notice) {
            is ToolNotice.LockedSkipped -> SketchEditorStrings.Toasts.KEY_LOCKED_SKIPPED to SketchEditorStrings.Toasts.lockedSkipped(notice.skipped)
            is ToolNotice.LockedNotErased -> SketchEditorStrings.Toasts.KEY_LOCKED_NOT_ERASED to SketchEditorStrings.Toasts.lockedNotErased(notice.skipped)
            ToolNotice.ZoomInForHandles -> SketchEditorStrings.Toasts.KEY_ZOOM_FOR_HANDLES to SketchEditorStrings.Toasts.ZOOM_FOR_HANDLES
            ToolNotice.NothingDrawn -> SketchEditorStrings.Toasts.KEY_NOTHING_DRAWN to SketchEditorStrings.Toasts.NOTHING_DRAWN
        }
        toast(key, text)
    }

    private fun toast(key: String, text: String) = notices.post(SketchNotice(key, BannerTier.Info, text), ports.clockMillis())

    // ---- edits ---------------------------------------------------------------------------------------------------

    /**
     * Applies [command] as one history step and returns why it was refused, or null. A sheet with constraints, and any command
     * with [solve] set (adding a constraint, setting a dimension), runs through [SolveRunner] and returns null at once: a refusal
     * of the solve shows as a notice and leaves the document where it was.
     */
    fun submit(command: Command, solve: Boolean = false): CommandError? {
        if (readOnly) return CommandError.Unsupported("sheet", "read-only")
        if (solve || doc.constraints.isNotEmpty() || pending.value > 0) {
            enqueue(command)
            return null
        }
        return Transaction.begin(doc).apply(command).flatMap { it.commit() }.flatMap { history.commit(it) }.fold(
            { error -> if (error == CommandError.NoChange) null else error.also(::refuse) },
            { next ->
                adopt(next, changedOf(next))
                null
            },
        )
    }

    private fun changedOf(next: HistoryV2): Set<ElementId> = next.present.scene.order.toSet() + doc.scene.order.toSet()

    private fun enqueue(command: Command) {
        pending.update { it + 1 }
        scope.launch {
            try {
                gate.withLock { runSolved(command) }
            } finally {
                pending.update { it - 1 }
            }
        }
    }

    private suspend fun runSolved(command: Command) {
        when (val outcome = runner.commitEdit(history.present, command)) {
            is SolveOutcome.Done -> {
                commitStep(outcome.step)
                analysis = ConstraintAnalysis.from(outcome)
            }
            is SolveOutcome.Rejected -> rejected(outcome.reason)
            is SolveOutcome.Unchanged, is SolveOutcome.Posed, is SolveOutcome.Cancelled -> Unit
        }
    }

    private fun commitStep(step: Committed) {
        history.commit(step).fold({ toast(SketchEditorStrings.Toasts.KEY_CONFLICT, SketchEditorStrings.Toasts.EDIT_CONFLICT) }, { adopt(it, step.changedIds) })
    }

    private fun rejected(reason: SolveRejection) {
        val state = when (reason) {
            is SolveRejection.Unsatisfiable -> SketchErrorState.ConstraintConflict
            is SolveRejection.EditFailed -> return refuse(reason.error)
            is SolveRejection.Crashed -> SketchErrorState.LowMemory
        }
        toast(SketchEditorStrings.Toasts.KEY_REFUSED, SketchErrorStrings.message(state))
    }

    private fun refuse(error: CommandError) {
        val text = when (error) {
            is CommandError.CapacityExceeded -> SketchErrorStrings.message(SketchErrorState.ElementCapReached)
            is CommandError.ConstraintCapExceeded -> SketchErrorStrings.message(SketchErrorState.ConstraintCapReached)
            else -> SketchEditorStrings.Toasts.EDIT_FAILED
        }
        toast(SketchEditorStrings.Toasts.KEY_REFUSED, text)
    }

    /** Takes [next] as the live history, refreshes what depends on the scene and hands the document to the saver. */
    private fun adopt(next: HistoryV2, changed: Collection<ElementId>) {
        history = next
        index.update(next.present.scene, changed)
        ui.selection = ui.selection.filterTo(LinkedHashSet()) { next.present.scene[ElementId(it)] != null }
        if (tool is SelectionAware && tool.id == ToolId.Select) tool = SelectTool(ui.selection.map(::ElementId), ports.touchPresent)
        revision += 1
        ports.saver?.submit(next.present)
        if (next.present.constraints.isEmpty()) analysis = ConstraintAnalysis.Unknown
    }

    fun undo() {
        if (!canUndo) return
        val before = history
        adopt(before.undo(), changedByStep(before, before.undo()))
        if (doc.constraints.isNotEmpty()) analyze()
    }

    fun redo() {
        if (!canRedo) return
        val before = history
        adopt(before.redo(), changedByStep(before, before.redo()))
        if (doc.constraints.isNotEmpty()) analyze()
    }

    private fun changedByStep(from: HistoryV2, to: HistoryV2): Set<ElementId> =
        (from.present.scene.order + to.present.scene.order).filterTo(LinkedHashSet()) { from.present.scene[it] != to.present.scene[it] }

    /** Replaces the whole document after the saver reloaded it from disk (the other device's version). */
    fun reloaded(doc: SketchDocumentV2) {
        history = HistoryV2.of(doc, ports.limits)
        index.rebuild(doc.scene)
        ui.selection = emptySet()
        revision += 1
        if (doc.constraints.isNotEmpty()) analyze() else analysis = ConstraintAnalysis.Unknown
    }

    /** Re-reads the degrees of freedom and conflicts for the status line; nothing is recorded in history. */
    fun analyze() {
        scope.launch {
            gate.withLock { analysis = ConstraintAnalysis.from(runner.analyze(history.present)) }
        }
    }

    /** Cancels a slow solve; the document keeps its last good pose. */
    fun cancelSolve() = runner.cancel()

    /** Selects [ids] (the Elements panel, Go to element) and gives them to the Select tool. */
    fun select(ids: List<ElementId>) {
        ui.selection = ids.mapTo(LinkedHashSet()) { it.value }
        if (ui.activeTool != ToolId.Select) ui.chooseTool(ToolId.Select)
        tool = SelectTool(ids, ports.touchPresent)
        preview = null
        configureAdapter()
    }

    // ---- keyboard ------------------------------------------------------------------------------------------------

    /**
     * Routes one key press through [KeyDispatcher] and runs what it decided. Returns true when the key was ours. [focus] says what
     * holds keyboard focus: while a text field or a dialog does, nothing fires (UX-22).
     */
    fun onKey(press: KeyPress, focus: KeyFocus = KeyFocus()): Boolean {
        val context = KeyContext(
            env = ports.keymap,
            singleKeyEnabled = focus.singleKeyEnabled,
            canvasFocused = focus.canvasFocused,
            textInputOrDialogFocused = focus.textInputOrDialogFocused || textSession != null,
            hasSelection = ui.selection.isNotEmpty(),
            handToolActive = ui.activeTool == ToolId.Hand,
        )
        return when (val outcome = KeyDispatcher.dispatch(press, context)) {
            is KeyOutcome.Handled -> run(outcome.action)
            else -> false
        }
    }

    /** Runs one decided key action; false for an action this controller does not own (clipboard, grid, region focus). */
    fun run(action: KeyAction): Boolean {
        val edit = KeyActionPlanner.plan(action, doc.scene, ui.selection.map(::ElementId), ports.ids)
        return when {
            action is KeyAction.SelectTool -> true.also { chooseTool(ToolId.valueOf(action.tool.name)) }
            action == KeyAction.ToolLock -> true.also { ui.toolLock = !ui.toolLock }
            action == KeyAction.Undo -> true.also { undo() }
            action == KeyAction.Redo -> true.also { redo() }
            action == KeyAction.Escape -> true.also { escape() }
            action == KeyAction.OpenShortcuts -> true.also { overlay = EditorOverlay.Shortcuts }
            action == KeyAction.OpenGoToElement -> true.also { overlay = EditorOverlay.GoToElement }
            action == KeyAction.OpenConstrainMenu -> true.also { overlay = EditorOverlay.ConstrainMenu }
            edit.command != null -> true.also { submit(edit.command) }
            action is KeyAction.Delete || action is KeyAction.Nudge || action is KeyAction.Arrange -> true.also { skippedLocked(edit) }
            else -> view(action)
        }
    }

    private fun skippedLocked(edit: EditPlan) {
        if (edit.skippedLocked.isNotEmpty()) toast(SketchEditorStrings.Toasts.KEY_LOCKED_SKIPPED, SketchEditorStrings.Toasts.lockedSkipped(edit.skippedLocked.size))
    }

    private fun escape() {
        if (preview != null || textSession != null || tool.id != ui.activeTool || tool !is SelectionAware) {
            val result = tool.onEvent(InputEvent.escape(ports.clockMillis()), context())
            result.next?.let { tool = it }
            preview = result.preview
            textSession = (tool as? TextTool.Editing)?.session
        } else {
            ui.selection = emptySet()
            tool = SelectTool(emptyList(), ports.touchPresent)
        }
        readSelection()
    }

    private fun view(action: KeyAction): Boolean {
        val centre = ScreenPx(canvasSize.x / HALF, canvasSize.y / HALF)
        val viewport = ui.viewport
        ui.viewport = when (action) {
            is KeyAction.Pan -> viewport.panBy(-action.dx * panStep(action.far), -action.dy * panStep(action.far))
            is KeyAction.PanPage -> viewport.panBy(0.0, -action.direction * canvasSize.y)
            KeyAction.ZoomIn -> viewport.zoomBy(centre, ZOOM_IN)
            KeyAction.ZoomOut -> viewport.zoomBy(centre, ZOOM_OUT)
            KeyAction.ResetZoom -> viewport.zoomTo(centre, 1.0)
            KeyAction.FitToContent, KeyAction.ZoomToFit -> fit()
            else -> return false
        }
        return true
    }

    /** The view that shows the whole scene, or the current one for an empty scene. */
    fun fit(): SketchViewport =
        ui.viewport.fit(dev.stapler.stelekit.sketch.v2.view.SceneBounds.of(doc.scene.elementsInOrder.asIterable()), canvasSize.x, canvasSize.y, FIT_PADDING_PX)

    private fun panStep(far: Boolean) = if (far) PAN_FAR_PX else PAN_PX

    // ---- life ----------------------------------------------------------------------------------------------------

    /** Saves what the editor holds now (leaving the sheet, a hidden page) and returns where that left the file. */
    suspend fun flush(): SaveState = ports.saver?.flush() ?: SaveState.Saved

    fun close() {
        ports.saver?.close()
        runner.close()
        scope.cancel()
    }

    private fun onUncaught(t: Throwable) {
        if (t is CancellationException) return
        ports.log.persistFailed(t::class.simpleName.orEmpty())
        toast(SketchEditorStrings.Toasts.KEY_REFUSED, SketchErrorStrings.message(SketchErrorState.LowMemory))
    }

    /** An edit that adds [element] on top, for a host that creates one directly (a drop, an import); the tool path never uses it. */
    fun add(element: dev.stapler.stelekit.sketch.v2.scene.SceneElement): CommandError? = submit(AddElement(element))

    private companion object {
        const val HALF = 2.0
        const val ZOOM_IN = 1.25
        const val ZOOM_OUT = 0.8
        const val PAN_PX = 40.0
        const val PAN_FAR_PX = 200.0
        const val FIT_PADDING_PX = 24.0
    }
}

/** Where keyboard focus is when a key arrives. */
data class KeyFocus(
    val canvasFocused: Boolean = true,
    val textInputOrDialogFocused: Boolean = false,
    /** The `SingleKeyShortcuts` setting (WCAG 2.1.4). */
    val singleKeyEnabled: Boolean = true,
)
