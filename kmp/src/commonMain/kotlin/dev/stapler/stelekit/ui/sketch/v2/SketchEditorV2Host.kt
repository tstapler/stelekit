// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.commands.LockElements
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.tools.SelectTool
import dev.stapler.stelekit.sketch.v2.tools.ToolContext
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/** Test tags of the host's own surfaces; the shell's tags are in [ShellTags]. */
object HostTags {
    const val ROOT = "sketch.host"
    const val CANVAS_INPUT = "sketch.host.canvas"
    const val LOADING = "sketch.host.loading"
}

/** How long the toast lane waits between re-reads while a toast with a deadline is up. */
private const val TOAST_TICK_MS = 500L

/**
 * The editor of one open v2 sheet (Story 6.2.0b): mounts [SketchScreenV2] with the [SketchEditorController] of [session], the
 * canvas, every panel and dialog, the banner slot and the toast lane. Nothing here decides an edit: the controller applies
 * every tool command, key and panel command as one undoable step and hands the document to the saver.
 */
@Composable
fun SketchEditorV2Host(
    session: SketchEditorSession,
    environment: ModernPreviewEnvironment,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    pointer: PointerClass = PointerClass.MouseOnly,
    window: WindowDp? = null,
    restore: RestoreMenuEntry? = null,
) {
    val controller = session.controller
    val state = controller.ui
    val extras = remember(session, environment) { EditorExtras(controller, session.services, environment) }
    SideEffect { session.extras = extras }
    DisposableEffect(session) {
        onDispose {
            extras.close()
            session.close()
        }
    }
    val saveState by controller.saves.state.collectAsState()
    val solving = controller.solveState.collectAsState().value.solving
    val toasts = remember(controller) { ToastLaneHost(controller.notices, { controller.notifier.now() }, ToastActions(onPrimary = { controller.notifier.act(it, controller::undo) })) }
    val banner = remember(controller) { BannerSlotState(controller.notices) }
    val entries by session.diagnostics.port.diagnostics.collectAsState()
    val empty = controller.doc.scene.order.isEmpty()
    LaunchedEffect(entries, controller.notifier.revision) {
        // The diagnostics host attaches the port's entries to the queue in its own effect; let it run first.
        yield()
        banner.refresh(empty)
        toasts.refresh()
        while (controller.notifier.hasTimedToast()) {
            delay(TOAST_TICK_MS)
            toasts.refresh()
        }
    }
    var singleKey by remember { mutableStateOf(true) }
    SideEffect { controller.tools.syncInput() }
    val config = SketchShellConfig(
        model = remember(controller) { ToolStripModel(technicalSheet = false, keymapEnv = controller.keymap) },
        pointer = pointer,
        status = ToolStripStatus(controller.canUndo, controller.canRedo, state.selection.size),
        actions = ToolStripActions(onUndo = controller::undo, onRedo = controller::redo, onDelete = { controller.keys.run(KeyAction.Delete) }, onConstrain = { controller.overlay = EditorOverlay.ConstrainMenu }),
        title = session.title,
        hasMultipleLayers = controller.doc.scene.layers.size > 1,
        onFit = { state.viewport = controller.view.fit() },
        banner = banner,
        sheetEmpty = empty,
        emptyActions = EmptyStateActions(onShortcuts = { controller.overlay = EditorOverlay.Shortcuts }),
        onShortcuts = { controller.overlay = EditorOverlay.Shortcuts },
        onBack = onBack,
        saveStatus = saveState.toStatus(),
        bannerActions = BannerActions(
            onPrimary = { controller.saves.bannerPrimary(it.row) },
            onMoreItem = { candidate, label -> controller.saves.bannerMore(candidate.row, label) },
        ),
        toasts = toasts,
        onGoToElement = { controller.overlay = EditorOverlay.GoToElement },
        selectionItems = selectionItems(controller),
        onSelectionAction = { controller.onSelectionAction(it, extras) },
        escRows = { controller.escRows() + extras.escRows() },
        onCanvasKey = { event -> extras.dimension.state == DimensionEditState.Closed && canvasKey(controller, event, singleKey) },
        constraintsAvailable = extras.constraintsAvailable,
        autoConstrain = extras.autoConstrain,
        onImport = { extras.importer.chooseFile() },
        importDisabledReason = extras.importDisabledReason,
        onExport = if (extras.exportAvailable) extras.exporter::open else null,
        extraChips = solveChips(controller, solving),
        grid = extras.grid,
        snap = extras.snap,
        onGrid = extras::toggleGrid,
        onSnap = extras::toggleSnap,
        restore = restore,
    )
    DiagnosticsHost(session.diagnostics, modifier.testTag(HostTags.ROOT)) {
        SketchScreenV2(
            state = state,
            config = config,
            window = window,
            panel = { tab -> SketchPanels(controller, tab, extras) },
            canvas = {
                SketchCanvasContent(controller, environment, pointer)
                ExtrasCanvasOverlays(controller, extras, pointer)
            },
        )
        HostOverlays(controller, extras, singleKey) { singleKey = it }
    }
}

/** The selection-menu entries this editor runs; Align, Order, Transform and Bind end open [ExtraPanel]s. */
private val MENU_ACTIONS = SelectionAction.entries.toSet()

private fun selectionItems(controller: SketchEditorController): List<SelectionAction> {
    val selected = controller.ui.selection.mapNotNull { controller.doc.scene[ElementId(it)] }
    if (selected.isEmpty()) return emptyList()
    return SelectionMenuModel.items(anyUnlocked = selected.any { !it.base.locked }, oneArrowSelected = selected.singleOrNull() is ArrowElement).filter { it in MENU_ACTIONS }
}

private fun SketchEditorController.onSelectionAction(action: SelectionAction, extras: EditorExtras) {
    val ids = ui.selection.map(::ElementId)
    when (action) {
        SelectionAction.Duplicate -> keys.run(KeyAction.Duplicate)
        SelectionAction.Group -> keys.run(KeyAction.Group)
        SelectionAction.Ungroup -> keys.run(KeyAction.Ungroup)
        SelectionAction.Lock -> submit(LockElements(ids, true))
        SelectionAction.Unlock -> submit(LockElements(ids, false))
        SelectionAction.Align, SelectionAction.Order, SelectionAction.Transform, SelectionAction.BindEnd -> extras.open(action)
    }
}

/** The Cancel chip of a solve that is running; it survives a collapsed status row (ux.md section 9). */
private fun solveChips(controller: SketchEditorController, solving: Boolean): List<ActionChip> =
    if (solving) listOf(SolveMessages.cancelChip(controller::cancelSolve)) else emptyList()

private fun canvasKey(controller: SketchEditorController, event: KeyEvent, singleKey: Boolean): Boolean {
    val press = event.toKeyPress() ?: return false
    val state = controller.ui
    if (event.key == Key.Escape && shellOwnsEscape(controller)) return false
    return controller.keys.onKey(press, KeyFocus(singleKeyEnabled = singleKey))
}

/** True when the first row of the one Esc order that applies is the shell's (the Pan pad, a help popup or the tour), so the canvas passes the press on. */
private fun shellOwnsEscape(controller: SketchEditorController): Boolean {
    val state = controller.ui
    val first = EscOrder.resolve(ShellEscape.shellRows(state) + controller.escRows())
    return first == EscRow.MenuOrFlyout || first == EscRow.Walkthrough
}

private fun SaveState.toStatus(): SaveStatus = when (this) {
    SaveState.Saved, SaveState.LeaseConflict -> SaveStatus.Saved
    SaveState.Pending -> SaveStatus.Unsaved
    SaveState.Saving -> SaveStatus.Saving
    is SaveState.Failed, is SaveState.Conflict -> SaveStatus.Failed
}

/** The canvas: the static scene, the live gesture preview, the selection frame, name tags and the text editor, with pointer and wheel input on top. */
@Composable
private fun BoxScope.SketchCanvasContent(controller: SketchEditorController, environment: ModernPreviewEnvironment, pointer: PointerClass) {
    val state = controller.ui
    val fontStatus = environment.metrics.fontStatus
    val builder = remember(environment) { DrawListBuilder(environment.metrics) }
    val previewBuilder = remember(environment) { DrawListBuilder(environment.metrics) }
    val painter = remember(environment) { ScenePainter(resources = environment.resources) }
    val layer = remember { StaticLayerHandle() }
    val ops = remember(controller.revision, fontStatus) { builder.build(controller.doc.scene, RenderStyle.Sketchy) }
    SideEffect { layer.set(ops, controller.spatialIndex) }
    val liveOps = remember(controller.tools.preview, fontStatus) {
        controller.tools.preview?.let { preview -> previewBuilder.build(sceneOf(preview.elements), RenderStyle.Sketchy) }.orEmpty()
    }
    val viewport = remember(state) { derivedStateOf { state.viewport } }
    Box(
        Modifier.fillMaxSize()
            .testTag(HostTags.CANVAS_INPUT)
            .onSizeChanged { controller.view.canvasSize = ScreenPx(it.width.toDouble(), it.height.toDouble()) }
            .sketchPointerInput(controller.tools.adapter) { controller.tools.onPointerActions(it) }
            .wheelInput(controller),
    ) {
        SketchCanvasV2(layer, liveOps, viewport, painter)
        SelectionVisuals(controller)
        ConstraintPickerOverlay(controller, pointer)
    }
    NameTagsOverlay(controller)
    controller.tools.textSession?.let { session ->
        TextEditorOverlay(session, state.viewport, hasHardwareKeyboard = pointer != PointerClass.TouchOrPen, onCommit = controller.tools::commitText)
    }
}

private fun sceneOf(elements: List<SceneElement>): SketchSceneV2 = elements.fold(SketchSceneV2.empty()) { scene, e -> scene.add(e).getOrNull() ?: scene }

@Composable
private fun SelectionVisuals(controller: SketchEditorController) {
    val state = controller.ui
    val ids = state.selection
    if (ids.isEmpty() || state.activeTool != ToolId.Select) return
    val ctx = remember(controller.revision, state.viewport.zoom, ids) {
        ToolContext(controller.doc.scene, state.viewport.zoom, controller.defaultStyle, controller.ids, controller.spatialIndex)
    }
    val select = remember(ids, controller.revision) { SelectTool(ids.map(::ElementId), controller.touchPresent) }
    SelectionOverlay(select.selectionFrame(ctx), select.handleLayout(ctx), state.viewport)
}

@Composable
private fun BoxScope.NameTagsOverlay(controller: SketchEditorController) {
    val state = controller.ui
    if (state.selection.isEmpty()) return
    val size = controller.view.canvasSize
    val layout = remember(controller.revision, state.viewport, state.selection, size) {
        NameTagsModel.layout(controller.doc.scene, state.viewport, size.x, size.y, NameTagSubjects(selected = state.selection.mapTo(LinkedHashSet(), ::ElementId)))
    }
    NameTags(layout, Modifier.align(Alignment.TopStart))
}

/** The wheel and trackpad scroll: it pans, and with Ctrl it zooms; the adapter holds the rule. */
private fun Modifier.wheelInput(controller: SketchEditorController): Modifier = pointerInput(controller) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            if (event.type != PointerEventType.Scroll) continue
            controller.tools.onWheel(
                WheelInput(
                    ScreenPx(change.position.x.toDouble(), change.position.y.toDouble()),
                    change.scrollDelta.x.toDouble(),
                    change.scrollDelta.y.toDouble(),
                    WheelUnit.Line,
                    zoomModifier = event.keyboardModifiers.isCtrlPressed,
                ),
            )
            change.consume()
        }
    }
}

@Composable
private fun HostOverlays(controller: SketchEditorController, extras: EditorExtras, singleKey: Boolean, onSingleKey: (Boolean) -> Unit) {
    ExtrasDialogs(extras)
    when (controller.overlay) {
        EditorOverlay.Shortcuts -> ShortcutsSheet(
            ShortcutsSheetModel.of(controller.keymap, singleKey),
            onSingleKeyChange = onSingleKey,
            onClose = { controller.overlay = null },
        )
        EditorOverlay.GoToElement -> GoToElementDialog(
            controller.doc.scene,
            onActivate = { entry ->
                controller.view.reveal(entry.id)
                controller.overlay = null
            },
            onDismiss = { controller.overlay = null },
        )
        EditorOverlay.ConstrainMenu -> ConstrainMenuDialog(controller, extras)
        null -> Unit
    }
}

/** Shown while the sheet loads or when the host cannot open it. */
@Composable
fun SketchEditorLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag(HostTags.LOADING), contentAlignment = Alignment.Center) {
        Text(SketchEditorStrings.Screens.LOADING, Modifier.padding(16.dp))
    }
}

internal fun SketchEditorController.boundsOfSelection(): SceneBounds? =
    SceneBounds.of(ui.selection.mapNotNull { doc.scene[ElementId(it)] })
