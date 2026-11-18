// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import dev.stapler.stelekit.sketch.v2.commands.Applied
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.Committed
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.SequenceCommand
import dev.stapler.stelekit.sketch.v2.commands.StylePatch
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.diagnostics.DiagnosticsPort
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveOutcome
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveState
import dev.stapler.stelekit.sketch.v2.render.SeedSource
import dev.stapler.stelekit.sketch.v2.render.SheetStyleDefaults
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaver
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.QualitySignals
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchErrorState
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.tools.AddElementCommand
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.sketch.v2.tools.ToolCommand
import dev.stapler.stelekit.sketch.v2.tools.ToolContext
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.tools.Tool
import dev.stapler.stelekit.sketch.v2.tools.ToolRegistry
import dev.stapler.stelekit.sketch.v2.tools.toCommand
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchErrorStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
    /** Where the controller raises a banner row of its own (the constraint warning); null raises none. */
    val diagnostics: DiagnosticsPort? = null,
    val keymap: KeymapEnv = KeymapEnv(isMac = false, isWeb = false),
    val touchPresent: Boolean = false,
    /** The onboarding sequencer the notice queue and the banner slot share; null shows no coach mark. */
    val sequencer: OnboardingSequencer? = null,
    /** Local, never-transmitted counters for the flag-exit criteria; null counts nothing. */
    val quality: QualitySignals? = null,
    /** Where a new shape's Sketchy seed comes from; null folds the clock with the sheet. */
    val seeds: SeedSource? = null,
)

/**
 * The state holder of one open v2 sheet (Story 6.2.0a): it owns the [SketchDocumentV2] and its [HistoryV2], and it is the one
 * place a finished gesture, a key or a panel command becomes an undoable edit. The parts around it each own one concern:
 * [tools] the active tool and the pointer, [keys] the keyboard, [view] pan, zoom and reveal, [saves] the saver, [constraintState]
 * the solve's report and [notifier] what the user is told. Platform-free: it takes no composition scope; it owns [scope] with a
 * [CoroutineExceptionHandler], so an `OutOfMemoryError` in a solve never reaches the platform's uncaught-exception handler (which
 * kills an Android process). Call [close] when the editor leaves.
 *
 * Edits run in ONE [Transaction] per gesture. A sheet with constraints, and any command that adds one, runs through
 * [SolveRunner] only (the controller never calls the solver); the edit and the solve are one history step. Other edits apply on
 * the calling thread. Edits never overlap: while a solve is in flight later edits wait behind it. Everything that reads or
 * writes the state the canvas draws from runs on [adoption]; only the solver and the saver run on [context].
 */
class SketchEditorController(
    initial: SketchDocumentV2,
    private val ports: EditorPorts,
    context: CoroutineContext = Dispatchers.Default,
    val ui: SketchEditorState = SketchEditorState(),
    /** When true the sheet is shown but no edit applies (the router said read-only or another tab holds the lease). */
    val readOnly: Boolean = false,
    /**
     * Where the controller adopts results into the state the canvas reads (history, revision, selection, the spatial index, the
     * notice queue). Pass the UI dispatcher: the solve itself still runs on [context]. Defaults to [context] (tests, single-thread targets).
     */
    val adoption: CoroutineContext = context,
) {
    private val scope = CoroutineScope(adoption + SupervisorJob() + CoroutineExceptionHandler { _, t -> onUncaught(t) })
    private val runner: SolveRunner = ports.solver ?: SolveRunner(ports.limits, context = context, log = ports.log)
    private val gate = Mutex()
    private var closed = false
    private val pending = MutableStateFlow(0)
    private val index = SpatialIndex()
    internal val creation = ElementCreation(initial, ports.seeds ?: ElementCreation.defaultSeeds(initial.sheetUuid, ports.clockMillis()))

    var history: HistoryV2 by mutableStateOf(HistoryV2.of(initial, ports.limits))
        private set

    /** Bumped on every document change; a draw list or an index is rebuilt when it moves. */
    var revision: Long by mutableLongStateOf(0L)
        private set

    var activeLayer: LayerId by mutableStateOf(LayerId.Default)

    /** The style new shapes take; the Style panel's defaults row writes it. */
    var defaultStyle: ElementStyle by mutableStateOf(creation.startingStyle())

    /** The sheet's style (Clean unless the sheet is Sketchy, OD-5). */
    val sheetStyle: SheetStyleDefaults get() = creation.sheetStyle

    var overlay: EditorOverlay? by mutableStateOf(null)

    /** Keys the host-level surfaces own (clipboard, grid, snap); asked after the view keys. */
    var extraKeys: (KeyAction) -> Boolean = { false }

    /** Rewrites a finished tool gesture before it is committed (snap, auto-constrain); null means the hook committed it itself. */
    var interceptToolCommand: (ToolCommand) -> ToolCommand? = { it }

    /** What undo and redo say through the live region ("Undid: move rectangle"); it mirrors the history. */
    private var undoLabels = UndoAnnouncer.Empty

    val doc: SketchDocumentV2 get() = history.present
    val canUndo: Boolean get() = history.canUndo && !readOnly
    val canRedo: Boolean get() = history.canRedo && !readOnly
    val solveState: StateFlow<SolveState> = runner.state

    /** The spatial index of the current scene, shared with the canvas for culling and with the tools for hit tests. */
    val spatialIndex: SpatialIndex get() = index

    val notifier = EditorNotifier(SketchNoticeQueue(ports.limits, ports.sequencer, ports.log), ports.clockMillis)
    val notices: SketchNoticeQueue get() = notifier.queue
    val constraintState = ConstraintState(runner, ports.diagnostics, notifier) { history.present }
    val saves = EditorSaves(ports.saver, scope, notifier, ::reloaded) { creation.forSave(history.present) }

    val tools = ToolSession(ui, ports, ::toolContext, notifier, readOnly, ::commitToolCommand)
    val view = EditorView(ui, { history.present }, tools::select, notifier)
    val keys = EditorKeys(
        ui, ports, { history.present },
        KeyEffects(
            chooseTool = tools::chooseTool, undo = ::undo, redo = ::redo, escape = tools::escape,
            open = { overlay = it }, submit = { submit(it) }, lockedSkipped = notifier::lockedSkipped, view = { view.apply(it) || extraKeys(it) },
            textEditOpen = { tools.textSession != null },
        ),
    )

    /** The Constrain flow: the armed picker and the highlighted rows. */
    val constraints = ConstraintCreation(
        doc = { history.present },
        selection = { ui.selection.map(::ElementId) },
        newId = { ConstraintId(ports.ids.next().value) },
        commit = { command -> submit(command, solve = true) },
        announce = notifier::announce,
    )

    /** The Esc rows this editor owns right now (ux.md section 17); the shell adds its own. */
    val escRows: () -> Set<EscRow> = {
        buildSet {
            if (overlay != null) add(EscRow.DialogOrSheet)
            if (tools.textSession != null) add(EscRow.TextOverlayOrDimensionField)
            if (solveState.value.solving) add(EscRow.Solving)
            if (constraints.picker != null) add(EscRow.ArmedPicker)
            if (tools.preview != null) add(EscRow.MidDraw)
            if (ui.activeTool != ToolId.Select) add(EscRow.ActiveTool)
            if (ui.selection.isNotEmpty()) add(EscRow.Selection)
        }
    }

    val keymap: KeymapEnv get() = ports.keymap
    val ids: ElementIdSource get() = ports.ids
    val touchPresent: Boolean get() = ports.touchPresent
    internal val log: SketchLog get() = ports.log

    init {
        index.rebuild(initial.scene)
        if (initial.constraints.isNotEmpty()) analyze()
        if (!readOnly) ports.quality?.beginSession()
    }

    private fun toolContext() = ToolContext(history.present.scene, ui.viewport.zoom, defaultStyle, ports.ids, index)

    /** A fresh idle tool for [id], for the keyboard path (Crosshair mode) that runs a tool beside the pointer's. */
    internal val newTool: (ToolId) -> Tool? = { id -> ports.tools.create(id) }

    internal fun commitToolCommand(raw: ToolCommand) {
        val command = interceptToolCommand(raw) ?: return
        val drawn = (command as? AddElementCommand)?.let(creation::seeded)
        val placed = if (drawn != null && activeLayer != LayerId.Default) {
            AddElementCommand(drawn.element.withBase(drawn.element.base.copy(layerId = activeLayer)))
        } else {
            drawn ?: command
        }
        placed.toCommand().fold(
            notifier::refuse,
            { if (submit(it) == null && drawn != null) ports.quality?.elementDrawn(history.present.sheetUuid) },
        )
    }

    /**
     * Applies [command] as one history step and returns why it was refused, or null. A sheet with constraints, and any command
     * with [solve] set (adding a constraint, setting a dimension), runs through [SolveRunner] and returns null at once: a refusal
     * of the solve shows as a notice and leaves the document where it was.
     */
    fun submit(command: Command, solve: Boolean = false): CommandError? {
        if (readOnly) return CommandError.Unsupported("sheet", "read-only")
        if (solve || history.present.constraints.isNotEmpty() || pending.value > 0) {
            enqueue { runSolved(command) }
            return null
        }
        val before = history.present
        return Transaction.begin(before).apply(command).flatMap { it.commit() }.flatMap { history.commit(it) }.fold(
            { error -> if (error == CommandError.NoChange) null else error.also(notifier::refuse) },
            { next ->
                undoLabels = undoLabels.recorded(CommandLabels.of(command, before.scene))
                adopt(next, before.scene.order.toSet() + next.present.scene.order.toSet())
                null
            },
        )
    }

    /**
     * Applies [command] now as one history step and returns the document it left, or why it was refused. For commands that touch no
     * constraint (an import, a paste): a sheet with a solve in flight refuses it rather than queue it.
     */
    fun commitNow(command: Command): Either<CommandError, Applied> {
        if (readOnly) return CommandError.Unsupported("sheet", "read-only").left()
        if (pending.value > 0) return CommandError.Unsupported("sheet", "busy").left()
        val before = history.present
        return Transaction.begin(before).apply(command).flatMap { it.commit() }.flatMap { step -> history.commit(step).map { step to it } }.map { (step, next) ->
            undoLabels = undoLabels.recorded(CommandLabels.of(command, before.scene))
            adopt(next, before.scene.order.toSet() + next.present.scene.order.toSet())
            Applied(next.present, step.changedIds)
        }.onLeft { if (it != CommandError.NoChange) notifier.refuse(it) }
    }

    /**
     * Runs a label-editor solve through the shared [SolveRunner] under the edit gate and adopts the step it committed, so the new
     * value and the geometry the solver moved are one history step. Returns the editor's outcome.
     */
    internal suspend fun runLabelEdit(edit: suspend (SolveRunner) -> SolveOutcome?): SolveOutcome? {
        pending.update { it + 1 }
        try {
            return gate.withLock {
                if (readOnly) return@withLock null
                val outcome = edit(runner)
                if (outcome is SolveOutcome.Done) {
                    undoLabels = undoLabels.recorded(CommandLabels.of(SequenceCommand(emptyList()), history.present.scene))
                    commitStep(outcome.step)
                    constraintState.solved(SequenceCommand(emptyList()), outcome)
                }
                outcome
            }
        } finally {
            pending.update { it - 1 }
        }
    }

    /**
     * Runs [work] under the edit gate and counts it in [pending] until it ends, even when the scope is already closed (ATOMIC starts
     * the body, whose `finally` then balances the count). Everything that holds the gate goes through here or [runLabelEdit].
     */
    private fun enqueue(work: suspend () -> Unit) {
        pending.update { it + 1 }
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                gate.withLock { work() }
            } finally {
                pending.update { it - 1 }
            }
        }
    }

    /** Runs [action] now, or behind the solves queued before it so its history step lands after theirs. */
    private fun afterSolves(action: () -> Unit) {
        if (pending.value == 0) action() else enqueue { action() }
    }

    private suspend fun runSolved(command: Command) {
        when (val outcome = runner.commitEdit(history.present, command)) {
            is SolveOutcome.Done -> {
                undoLabels = undoLabels.recorded(CommandLabels.of(command, history.present.scene))
                commitStep(outcome.step)
                constraintState.solved(command, outcome)
            }
            is SolveOutcome.Rejected -> constraintState.refused(command, outcome)
            is SolveOutcome.Unchanged, is SolveOutcome.Posed, is SolveOutcome.Cancelled -> Unit
        }
    }

    private fun commitStep(step: Committed) {
        history.commit(step).fold(
            { notifier.toast(SketchEditorStrings.Toasts.KEY_CONFLICT, SketchEditorStrings.Toasts.EDIT_CONFLICT) },
            { adopt(it, step.changedIds) },
        )
    }

    /** Takes [next] as the live history, refreshes what depends on the scene and hands the document to the saver. */
    private fun adopt(next: HistoryV2, changed: Collection<ElementId>) {
        history = next
        index.update(next.present.scene, changed)
        ui.selection = ui.selection.filterTo(LinkedHashSet()) { next.present.scene[ElementId(it)] != null }
        tools.selectionPruned()
        revision += 1
        saves.submit(creation.forSave(next.present))
        if (next.present.constraints.isEmpty()) constraintState.cleared()
    }

    fun undo() = afterSolves {
        val (text, next) = undoLabels.undone()
        if (canUndo) undoLabels = next
        step(history.undo())
        notifier.announce(text)
    }

    fun redo() = afterSolves {
        val (text, next) = undoLabels.redone()
        if (canRedo) undoLabels = next
        step(history.redo())
        notifier.announce(text)
    }

    private fun step(to: HistoryV2) {
        val from = history
        if (readOnly || to === from) return
        val changed = (from.present.scene.order + to.present.scene.order).filterTo(LinkedHashSet()) { from.present.scene[it] != to.present.scene[it] }
        adopt(to, changed)
        if (to.present.constraints.isNotEmpty()) analyze()
    }

    /** Replaces the whole document after the saver reloaded it from disk (the other device's version). */
    fun reloaded(doc: SketchDocumentV2) {
        history = HistoryV2.of(doc, ports.limits)
        creation.reload(doc)
        undoLabels = UndoAnnouncer.Empty
        index.rebuild(doc.scene)
        ui.selection = emptySet()
        revision += 1
        if (doc.constraints.isNotEmpty()) analyze() else constraintState.cleared()
    }

    /** Re-reads the degrees of freedom and conflicts for the status line; nothing is recorded in history. */
    fun analyze() = enqueue { constraintState.analyze() }

    /** Cancels a slow solve; the document keeps its last good pose. */
    fun cancelSolve() = runner.cancel()

    /** Applies the style [patch] to the defaults new shapes take. */
    fun setDefaults(patch: StylePatch) {
        defaultStyle = defaultStyle.patched(patch)
    }

    /** The action of a conflict card button: a command goes through the solve runner, the rest only changes the view. */
    fun onConflictAction(action: ConflictAction) =
        constraintState.onAction(action, { submit(it, solve = true) }) { constraints.highlighted = it }

    /** Solves [submit] queued and not yet finished; a host test waits for 0 because the canvas going idle does not wait for a solve on the solver dispatcher. */
    internal val pendingSolves: StateFlow<Int> get() = pending

    /**
     * Saves what the editor holds now (leaving the sheet, a hidden page) and returns where that left the file. Solves already queued
     * finish first, because an edit reaches the saver only when its solve is adopted; one that outlasts [SOLVE_WAIT_MS] leaves the
     * state unsaved so the caller prompts instead of dropping the edit.
     */
    suspend fun flush(): SaveState {
        val settled = withTimeoutOrNull(SOLVE_WAIT_MS) { pending.first { it == 0 } } != null
        val state = saves.flush()
        return if (settled || state.hasUnsavedWork) state else SaveState.Pending
    }

    fun close() {
        if (closed) return
        closed = true
        if (!readOnly) ports.quality?.endSession(ports.log)
        saves.close()
        runner.close()
        scope.cancel()
    }

    private fun onUncaught(t: Throwable) {
        if (t is CancellationException) return
        ports.log.persistFailed(t::class.simpleName.orEmpty())
        // The handler runs on whichever thread threw; the notice queue is adoption-confined.
        scope.launch { notifier.toast(SketchEditorStrings.Toasts.KEY_REFUSED, SketchErrorStrings.message(SketchErrorState.LowMemory)) }
    }
}

private const val SOLVE_WAIT_MS = 10_000L

/** [this] with every field [patch] sets replaced; a sharp-corners patch clears the roundness. */
internal fun ElementStyle.patched(patch: StylePatch): ElementStyle = copy(
    strokeColor = patch.strokeColor ?: strokeColor,
    backgroundColor = patch.backgroundColor ?: backgroundColor,
    fillStyle = patch.fillStyle ?: fillStyle,
    strokeWidth = patch.strokeWidth ?: strokeWidth,
    strokeStyle = patch.strokeStyle ?: strokeStyle,
    roughness = patch.roughness ?: roughness,
    roundness = if (patch.sharpCorners) null else patch.roundness ?: roundness,
)
