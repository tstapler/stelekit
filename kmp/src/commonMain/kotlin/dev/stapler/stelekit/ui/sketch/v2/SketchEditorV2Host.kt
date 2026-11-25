// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.commands.LockElements
import dev.stapler.stelekit.ui.PlatformBackHandler
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.UnderlayIds
import dev.stapler.stelekit.ui.sketch.v2.guide.GuideEffects
import dev.stapler.stelekit.ui.sketch.v2.guide.GuideRunner
import dev.stapler.stelekit.ui.sketch.v2.guide.TourTriggers
import dev.stapler.stelekit.ui.sketch.v2.guide.GuideContent
import dev.stapler.stelekit.ui.sketch.v2.guide.HelpSearchDialog
import dev.stapler.stelekit.ui.sketch.v2.guide.TourController
import dev.stapler.stelekit.sketch.v2.tools.SelectTool
import dev.stapler.stelekit.sketch.v2.tools.ToolContext
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchWebStorageStrings
import kotlinx.coroutines.CoroutineExceptionHandler
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

/** How often the web storage status is re-read: the temporary-graph and private-window flags can change while a sheet is open. */
private const val STORAGE_STATUS_POLL_MS = 5_000L

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
    // A touch or pen contact proves the device has one: from then on the 44 dp floors apply (UX-46), even on a desktop that started mouse-only.
    var seenTouch by remember(session) { mutableStateOf(false) }
    SideEffect { controller.tools.adapter.onKindSeen = { kind -> if (kind != PointerKind.Mouse) seenTouch = true } }
    val pointer = if (seenTouch) PointerClass.TouchOrPen else pointer
    val state = controller.ui
    val extras = remember(session, environment) { EditorExtras(controller, session.services, environment, session.diagnostics.port) }
    SideEffect { session.extras = extras }
    if (controller.keymap.isWeb) StillSavingEffect(controller)
    val storageStatus by produceState(session.services.platform.storageStatus(), session) {
        while (controller.keymap.isWeb) {
            delay(STORAGE_STATUS_POLL_MS)
            value = session.services.platform.storageStatus()
        }
    }
    val cue = remember(session, storageStatus) {
        DurabilityCueHost.viewOf(storageStatus) { controller.notifier.toast(SketchWebStorageStrings.Cue.TOAST_KEY, it) }
    }
    val largeScene = remember(session) { EditorLargeScene(controller, session.diagnostics.port, session.services) }
    val canvasAccess = remember(session, window) { EditorCanvasAccess(controller, window) }
    val hint = remember(session) { SingleKeyHintProducer(session.diagnostics.port, controller.keymap.isWeb) }
    val technical = remember(session) { session.services.technical?.let { source -> session.sheet?.let { TechnicalPanelState(source, it) } } }
    SideEffect { controller.keys.hint = hint }
    val tours = remember(session) { session.services.platform.tourProgress?.let { TourController(GuideContent.registry(), it) } }
    TourTriggers(tours, controller.ui.activeTool, controller.overlay == EditorOverlay.ConstrainMenu, controller.ui.panelTab, controller.ui.selection.singleOrNull()?.let { controller.doc.scene[ElementId(it)] } is LineElement, technicalShapeSelected = technicalShapeSelected(controller, extras))
    LaunchedEffect(controller.revision) { largeScene.refresh() }
    DisposableEffect(extras) { onDispose { extras.close() } }
    val latestBack by rememberUpdatedState(onBack)
    // Owned here, not rememberCoroutineScope: the remembered LeaveGuard would outlive that scope (ScopeEscapeAuditTest).
    val leaveScope = remember(session) {
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + dev.stapler.stelekit.coroutines.PlatformDispatcher.Main +
                CoroutineExceptionHandler { _, t -> controller.log.persistFailed(t::class.simpleName.orEmpty()) },
        )
    }
    val leave = remember(session) { LeaveGuard(controller::flush, leaveScope) { latestBack() } }
    PlatformBackHandler(enabled = true) { leave.requestLeave() }
    DisposableEffect(session) {
        onDispose {
            largeScene.close()
            leaveScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            session.closeAfterSave(discard = leave.discarded)
        }
    }
    val saveState by controller.saves.state.collectAsState()
    val solving = controller.solveState.collectAsState().value.solving
    val toasts = remember(controller) { ToastLaneHost(controller.notices, { controller.notifier.now() }, ToastActions(onPrimary = { controller.notifier.act(it, controller::undo) }, onMoreItem = { notice, item -> controller.onToastMore(notice, item) })) }
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
        model = remember(controller, controller.sheetKind) { ToolStripModel(technicalSheet = controller.sheetKind == dev.stapler.stelekit.sketch.v2.constraints.SheetKind.Technical, keymapEnv = controller.keymap) },
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
        onBack = leave::requestLeave,
        onFingerDrawingChosen = { extras.finger?.choose(it) },
        saveStatus = saveState.toStatus(),
        bannerActions = BannerActions(
            onPrimary = { candidate ->
                when (candidate.row) {
                    BannerRowId.LargeDrawing -> largeScene.reenableEffects()
                    BannerRowId.SingleKeyChip -> {
                        singleKey = true
                        hint.resolve()
                    }
                    BannerRowId.PenDetected -> extras.finger?.backOn()
                    BannerRowId.FingerHintOn -> extras.finger?.choose(false)
                    BannerRowId.FingerHintOff -> extras.finger?.choose(true)
                    else -> controller.saves.bannerPrimary(candidate.row)
                }
            },
            onMoreItem = { candidate, label -> controller.saves.bannerMore(candidate.row, label) },
        ),
        toasts = toasts,
        onGoToElement = { controller.overlay = EditorOverlay.GoToElement },
        onOutline = { controller.overlay = EditorOverlay.Outline },
        onAddShapeAt = if (controller.readOnly) null else ({ controller.overlay = EditorOverlay.AddShapeAt }),
        selectionItems = selectionItems(controller) + extras.engine.menuItems(),
        onSelectionAction = { controller.onSelectionAction(it, extras) },
        escRows = { controller.escRows() + extras.escRows() },
        onCanvasKey = { event -> extras.dimension.state == DimensionEditState.Closed && canvasKey(controller, extras, event, singleKey) },
        constraintsAvailable = extras.constraintsAvailable,
        autoConstrain = extras.autoConstrain,
        loupe = extras.loupe,
        lengthUnit = extras.lengthUnit.takeIf { controller.sheetKind == dev.stapler.stelekit.sketch.v2.constraints.SheetKind.Technical },
        onImport = { extras.importer.chooseFile() },
        importDisabledReason = extras.importDisabledReason,
        onExport = if (extras.exportAvailable) extras.exporter::open else null,
        extraChips = solveChips(controller, solving) + extras.access.chips() + listOfNotNull(extras.underlay.chip()),
        priorityChips = textSizeChips(controller) + planChips(controller) + technicalChips(controller, extras, extras.constraintsAvailable, technical != null),
        edgeStripAtEnd = extras.input.stripAtEnd,
        durabilityCue = cue,
        grid = extras.grid,
        snap = extras.snap,
        onGrid = extras::toggleGrid,
        onSnap = extras::toggleSnap,
        onSnapHold = { extras.snapHeld = it },
        snapHeld = extras.snapHeld,
        snapChoices = extras.snapChoices,
        restore = restore,
        sheetKind = controller.sheetKind.takeIf { it != dev.stapler.stelekit.sketch.v2.constraints.SheetKind.Converted && !controller.readOnly },
        onSheetKind = { controller.setSheetKind(it) },
        technicalPanel = technical != null && controller.sheetKind == dev.stapler.stelekit.sketch.v2.constraints.SheetKind.Technical,
        canvasModifier = remember(canvasAccess, extras) { canvasRegionKeysOf(canvasAccess) { !extras.pills.fieldFocused } },
        onRegions = { canvasAccess.regions = it },
        tours = tours,
        onHelp = { controller.overlay = EditorOverlay.HelpSearch },
    )
    DiagnosticsHost(session.diagnostics, modifier.testTag(HostTags.ROOT)) {
        SketchScreenV2(
            state = state,
            config = config,
            window = window,
            panel = { tab -> SketchPanels(controller, tab, extras, technical) },
            canvas = {
                SketchCanvasContent(controller, environment, pointer, extras, largeScene.degradation)
                ExtrasCanvasOverlays(controller, extras, pointer, glyphsHidden = largeScene.degradation.has(DegradationStep.ConstraintGlyphsHidden))
                PlatformInputOverlays(extras.input)
                UnderlayBar(extras.underlay)
                ProvenanceMarksOverlay(controller)
                ProvenanceBar(controller)
                CanvasAccessLayer(controller, canvasAccess)
                CrosshairLayer(extras.access, state.viewport, canvasAccess.regions)
                largeScene.hardCap?.let { HardCapNotice(it, largeScene::answer, Modifier.align(Alignment.TopCenter)) }
            },
        )
        HostOverlays(controller, extras, tours, singleKey) { singleKey = it }
        if (leave.prompting) {
            LeaveSketchPrompt(
                onRetry = leave::retry,
                onExportCopy = {
                    leave.keepEditing()
                    extras.exporter.open()
                },
                onDiscard = leave::discard,
                onKeepEditing = leave::keepEditing,
                canExport = extras.exportAvailable,
            )
        }
    }
}

/** True when a shape with dimension pills is selected on a technical sheet: the first time is the dimensions tour's cue. */
private fun technicalShapeSelected(controller: SketchEditorController, extras: EditorExtras): Boolean {
    val selection = controller.ui.selection
    val technical = controller.sheetKind == dev.stapler.stelekit.sketch.v2.constraints.SheetKind.Technical
    if (!DimensionPillModel.shown(technical, controller.readOnly, extras.pills.visible, selection.size, controller.ui.activeTool)) return false
    return selection.singleOrNull()?.let { controller.doc.scene[ElementId(it)] }?.let { DimensionPillModel.specs(it).isNotEmpty() } == true
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
        SelectionAction.EditLabel, SelectionAction.EditPoints, SelectionAction.UnbindStart, SelectionAction.UnbindEnd -> extras.engine.run(action)
    }
}

/** The Cancel chip of a solve that is running; it survives a collapsed status row (ux.md section 9). */
private fun solveChips(controller: SketchEditorController, solving: Boolean): List<ActionChip> =
    if (solving) listOf(SolveMessages.cancelChip(controller::cancelSolve)) else emptyList()

private fun canvasKey(controller: SketchEditorController, extras: EditorExtras, event: KeyEvent, singleKey: Boolean): Boolean {
    val press = event.toKeyPress() ?: return false
    if (event.key == Key.Escape && shellOwnsEscape(controller, extras)) return false
    val focus = KeyFocus(singleKeyEnabled = singleKey)
    val rows = { controller.escRows() + extras.escRows() + ShellEscape.shellRows(controller.ui) }
    return extras.access.onKey(press, focus, rows) || controller.keys.onKey(press, focus)
}

/** Tab, F6 and Esc across the canvas stops (skip link, elements) and out to the other regions of the one tab order. */
private fun canvasRegionKeysOf(access: EditorCanvasAccess, enabled: () -> Boolean): Modifier = Modifier.canvasRegionKeys(
    focus = { access.focus },
    stops = { access.tree().tabStops },
    present = { access.present },
    moveWithin = access::moveWithin,
    moveOut = access::moveOut,
    enabled = enabled,
)

/** True when the first row of the one Esc order that applies is the shell's (the Pan pad, a help popup or the tour), so the canvas passes the press on. */
private fun shellOwnsEscape(controller: SketchEditorController, extras: EditorExtras): Boolean {
    val state = controller.ui
    val first = EscOrder.resolve(ShellEscape.shellRows(state) + controller.escRows() + extras.escRows())
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
private fun BoxScope.SketchCanvasContent(
    controller: SketchEditorController,
    environment: ModernPreviewEnvironment,
    pointer: PointerClass,
    extras: EditorExtras,
    degradation: Degradation,
) {
    val input = extras.input
    val state = controller.ui
    val fontStatus = environment.metrics.fontStatus
    val builder = remember(environment) { DrawListBuilder(environment.metrics) }
    val painter = remember(extras) { ScenePainter(resources = extras.painterResources) }
    val layer = remember { StaticLayerHandle() }
    val style = degradation.effectiveStyle(RenderStyle.Sketchy)
    val ops = remember(controller.revision, fontStatus, style) { builder.build(controller.doc.scene, style) }
    val shown = controller.tools.preview ?: extras.access.preview
    // While the photo is being moved its preview shows the new place, so the committed copy must not also show the old one.
    val underlayMoving = shown?.elements?.any { it.id == UnderlayIds.ELEMENT } == true
    SideEffect { layer.set(if (underlayMoving) splitForGesture(ops, setOf(UnderlayIds.ELEMENT)).static else ops, controller.spatialIndex) }
    val liveOps = remember(shown, fontStatus, style) {
        shown?.let { preview -> LivePreviewOps.build(environment.metrics, preview.elements, style) }.orEmpty()
    }
    val pxPerDp = LocalDensity.current.density.toDouble()
    SideEffect { controller.view.pxPerDp = pxPerDp }
    val viewport = remember(state) { derivedStateOf { state.viewport } }
    Box(
        Modifier.fillMaxSize()
            .testTag(HostTags.CANVAS_INPUT)
            .onSizeChanged { controller.view.canvasSize = ScreenPx(it.width.toDouble(), it.height.toDouble()) }
            .platformPointerInput(controller, input)
            .wheelInput(controller)
            .annotationTapInput(extras.annotationTaps),
    ) {
        SketchCanvasV2(layer, liveOps, viewport, painter)
        ConstraintPickerOverlay(controller, pointer)
    }
    UnderlayReferences(controller, extras.underlay)
    GridOverlay(extras.visibleGrid, state.viewport)
    NameTagsOverlay(controller)
    // After the name tags, so a tiny shape's handles are drawn over its label and never hidden by it (the overlay takes no pointer input).
    SelectionVisuals(controller)
    TextSizeBadge(controller)
    SnapGuideOverlay(controller.tools.preview?.guides.orEmpty(), state.viewport, haptics = state.lastInput == InputKind.TouchOrPen)
    SnapPointMarker(controller.tools.preview?.point, state.viewport)
    TouchLoupe(controller.tools.loupe, state.touchLoupe, { controller.view.canvasSize }, { loupeFocus(controller) }, pxPerDp) { region ->
        // Its own painter, so the loupe's cull and path cache never touch the main canvas's.
        val loupePainter = remember(extras) { ScenePainter(resources = extras.painterResources) }
        SketchCanvasV2(layer, liveOps, viewport, loupePainter, region = region)
        // The copy takes no clicks: the Zoom chip is left out so nothing inside the bubble can be hit.
        SelectionVisuals(controller, interactive = false)
    }
    controller.tools.textSession?.let { session ->
        TextEditorOverlay(session, state.viewport, hasHardwareKeyboard = pointer != PointerClass.TouchOrPen, onCommit = controller.tools::commitText)
    }
}

/** The snapped point the active tool acts on, in canvas pixels; null when the tool shows none (the loupe then follows the finger). */
private fun loupeFocus(controller: SketchEditorController): ScreenPx? =
    controller.tools.preview?.cursor?.let { controller.ui.viewport.toScreen(it) }

@Composable
private fun SelectionVisuals(controller: SketchEditorController, interactive: Boolean = true) {
    val state = controller.ui
    val ids = state.selection
    if (ids.isEmpty() || state.activeTool != ToolId.Select) return
    val ctx = remember(controller.revision, state.viewport.zoom, ids) {
        ToolContext(controller.doc.scene, state.viewport.zoom, controller.defaultStyle, controller.ids, controller.spatialIndex, controller.view.pxPerDp)
    }
    val select = remember(ids, controller.revision) { SelectTool(ids.map(::ElementId), controller.touchPresent) }
    // While a move or resize runs, the handles ride the snapped preview frame instead of staying at the pre-drag one.
    val dragged = controller.tools.preview?.frame
    SelectionOverlay(
        dragged ?: select.selectionFrame(ctx),
        select.handleLayout(ctx, around = dragged ?: select.selectionFrame(ctx)),
        state.viewport,
        onZoomToSelection = if (interactive) controller.view::zoomToSelection else null,
        vertices = select.vertexPoints(ctx),
    )
}

@Composable
private fun BoxScope.NameTagsOverlay(controller: SketchEditorController) {
    val state = controller.ui
    if (state.selection.isEmpty()) return
    // A tiny shape on touch shows corner handles over where its label would sit: skip the chip, but still name it for screen readers.
    val ids = state.selection.mapTo(LinkedHashSet(), ::ElementId)
    val covered = state.activeTool == ToolId.Select && run {
        val ctx = ToolContext(controller.doc.scene, state.viewport.zoom, controller.defaultStyle, controller.ids, controller.spatialIndex, controller.view.pxPerDp)
        NameTagsModel.hiddenByCompactHandles(controller.doc.scene, ids, SelectTool(ids.toList(), controller.touchPresent).handleLayout(ctx))
    }
    if (covered) {
        val name = controller.doc.scene[ids.single()]?.let(ElementNames::nameOf).orEmpty()
        Box(Modifier.size(0.dp).semantics { contentDescription = name })
        return
    }
    val size = controller.view.canvasSize
    val layout = remember(controller.revision, state.viewport, state.selection, size) {
        NameTagsModel.layout(controller.doc.scene, state.viewport, size.x, size.y, NameTagSubjects(selected = state.selection.mapTo(LinkedHashSet(), ::ElementId)))
    }
    NameTags(layout, Modifier.align(Alignment.TopStart))
}

/** Compose's pointer events feed the adapter through the platform's palm and cancel seams, unless the platform supplies its own samples (the web). */
private fun Modifier.platformPointerInput(controller: SketchEditorController, input: PlatformInputHost): Modifier =
    if (input.usesRawPointers) {
        this
    } else {
        sketchPointerInput(controller.tools.adapter, input.palm, input.cancel) { controller.tools.onPointerActions(it) }
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
private fun HostOverlays(controller: SketchEditorController, extras: EditorExtras, tours: TourController?, singleKey: Boolean, onSingleKey: (Boolean) -> Unit) {
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
        EditorOverlay.Outline -> OutlineHost(controller)
        EditorOverlay.AddShapeAt -> AddShapeAtHost(controller, extras)
        EditorOverlay.ConstrainMenu -> ConstrainMenuDialog(controller, extras)
        EditorOverlay.HelpSearch -> GuideDialog(controller, extras, tours)
        EditorOverlay.WallLength -> WallLengthDialog(controller, extras)
        null -> Unit
    }
}

/** Shown while the sheet loads or when the host cannot open it. */
@Composable
fun SketchEditorLoading(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().testTag(HostTags.LOADING).semantics {
            liveRegion = LiveRegionMode.Polite
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            contentDescription = SketchEditorStrings.Screens.LOADING
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(SketchEditorStrings.Screens.LOADING, Modifier.padding(16.dp))
    }
}

internal fun SketchEditorController.boundsOfSelection(): SceneBounds? =
    SceneBounds.of(ui.selection.mapNotNull { doc.scene[ElementId(it)] })

/** Help and search: the tools this sheet offers and the actions this host can run. */
@Composable
private fun GuideDialog(controller: SketchEditorController, extras: EditorExtras, tours: TourController?) {
    val tools = remember(controller.sheetKind) { ToolStripModel(technicalSheet = controller.sheetKind == dev.stapler.stelekit.sketch.v2.constraints.SheetKind.Technical, keymapEnv = controller.keymap).tools.map { it.id } }
    val actions = GuideRunner.offered(extras.constraintsAvailable, extras.exportAvailable, extras.importDisabledReason == null)
    HelpSearchDialog(
        tours = tours,
        tools = tools,
        actions = actions,
        onRun = { action ->
            controller.overlay = null
            GuideRunner.run(action, editorGuideEffects(controller, extras))
        },
        onDismiss = { controller.overlay = null },
    )
}

private fun editorGuideEffects(controller: SketchEditorController, extras: EditorExtras) = object : GuideEffects {
    override fun undo() = controller.undo()
    override fun redo() = controller.redo()
    override fun delete() { controller.keys.run(KeyAction.Delete) }
    override fun constrain() { controller.overlay = EditorOverlay.ConstrainMenu }
    override fun shortcuts() { controller.overlay = EditorOverlay.Shortcuts }
    override fun goToElement() { controller.overlay = EditorOverlay.GoToElement }
    override fun fit() { controller.ui.viewport = controller.view.fit() }
    override fun grid() = extras.toggleGrid()
    override fun snap() = extras.toggleSnap()
    override fun export() = extras.exporter.open()
    override fun import() { extras.importer.chooseFile() }
    override fun useTool(tool: ToolId) = controller.tools.chooseTool(tool)
}
