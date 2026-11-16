// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

/** Window size in dp; the host passes it, or [SketchScreenV2] measures it. */
data class WindowDp(val width: Int, val height: Int)

/** Everything the editor shell shows besides the canvas and the panels. */
data class SketchShellConfig(
    val model: ToolStripModel,
    val pointer: PointerClass,
    val status: ToolStripStatus = ToolStripStatus(),
    val actions: ToolStripActions = ToolStripActions(),
    val title: String = "",
    val hasMultipleLayers: Boolean = false,
    /** Space the host's own overlay (the shell mic button) takes at the bottom end, which the view cluster keeps clear of. */
    val endInset: Dp = 0.dp,
    /** "Pan arrows: Auto / Always / Never" (UX-120). */
    val panArrows: PanArrows = PanArrows.Auto,
    /** An edge-pan strip is active on the bottom-end side, so the cluster and the pad stay clear of its protected span. */
    val edgeStripAtEnd: Boolean = false,
    /** The pan pad's centre "Fit" (the host knows the scene bounds). */
    val onFit: () -> Unit = {},
    /** The user toggled Finger drawing from the top-bar chip: the host stores it as an explicit choice (UX-80). */
    val onFingerDrawingChosen: (Boolean) -> Unit = {},
    /** The one banner slot and the onboarding sequencer (Tasks 3.6.8b, 3.6.6c); null shows neither the coach mark nor the empty state. */
    val banner: BannerSlotState? = null,
    /** The sheet holds no element: the empty state may show (Task 3.6.6c). */
    val sheetEmpty: Boolean = false,
    val emptyActions: EmptyStateActions = EmptyStateActions(),
    /** Opens the shortcuts sheet (the overflow entry "Keyboard shortcuts"). */
    val onShortcuts: () -> Unit = {},
)

object ShellTags {
    const val TOP_BAR = "sketch.topbar"
    const val FINGER_CHIP = "sketch.finger"
    const val OVERFLOW = "sketch.overflow"
    const val OVERFLOW_CONSTRAIN = "sketch.overflow.constrain"
    fun overflowTool(id: ToolId) = "sketch.overflow.tool.${id.name.lowercase()}"
    const val CANVAS = "sketch.canvas"
    const val CHIP_ROW = "sketch.chips"
    const val TOOL_LOCK_CHIP = "sketch.chip.toollock"
    const val VIEW_CLUSTER = "sketch.viewcluster"
    const val OVERFLOW_PAN = "sketch.overflow.pan"
    const val OVERFLOW_SHORTCUTS = "sketch.overflow.shortcuts"
    const val OVERFLOW_TIPS = "sketch.overflow.tips"
    const val BANNER = "sketch.banner"
    const val HINT = "sketch.hint"
    const val PANEL = "sketch.panel"
    fun tab(tab: PanelTab) = "sketch.tab.${tab.name.lowercase()}"
    fun layout(placement: StripPlacement) = "sketch.layout.${placement.name.lowercase()}"
}

/**
 * The adaptive editor shell (Story 3.1.3): the dock layout (strip bottom-docked, panels as a bottom sheet) or the rail
 * layout (strip as a left rail, panels in a persistent pane or a side sheet), chosen from BOTH window axes by
 * [ShellLayout.choose]. All editor state lives in [state], owned by the caller, so a fold, unfold or rotation switches
 * the layout without losing the selection, the tool, the open tab, the view or the text being typed (UX-93).
 * [canvas] fills the canvas area; [panel] draws one tab's content.
 */
@Composable
fun SketchScreenV2(
    state: SketchEditorState,
    config: SketchShellConfig,
    modifier: Modifier = Modifier,
    window: WindowDp? = null,
    panel: @Composable (PanelTab) -> Unit = {},
    canvas: @Composable BoxScope.() -> Unit = {},
) {
    if (window != null) {
        SketchShell(state, config, window, panel, modifier, canvas)
    } else {
        BoxWithConstraints(modifier) {
            SketchShell(state, config, WindowDp(maxWidth.value.toInt(), maxHeight.value.toInt()), panel, Modifier, canvas)
        }
    }
}

/** The controls the walkthrough and its end return focus to. */
private class ShellFocus(val overflow: FocusRequester, val canvas: FocusRequester) {
    fun restore(key: String) {
        val target = if (key == OVERFLOW_FOCUS) overflow else canvas
        runCatching { target.requestFocus() }
    }
}

private const val OVERFLOW_FOCUS = "overflow"

@Composable
private fun SketchShell(
    state: SketchEditorState,
    config: SketchShellConfig,
    window: WindowDp,
    panel: @Composable (PanelTab) -> Unit,
    modifier: Modifier = Modifier,
    canvas: @Composable BoxScope.() -> Unit,
) {
    val layout = ShellLayout.choose(window.width, window.height)
    val extent = if (layout.placement == StripPlacement.BottomDock) window.width else window.height
    val focus = remember { ShellFocus(FocusRequester(), FocusRequester()) }
    val grouped = !config.model.isFlat(extent)
    val driver = remember(extent, config.model) { WalkthroughDriver({ state.lastInput }, { grouped }, focus::restore) }
    val strip: @Composable (Modifier) -> Unit = { m ->
        ToolStrip(config.model, state, layout.placement, extent, config.pointer, config.status, config.actions, m.anchorFor(state, WalkthroughStep.Tools))
    }
    val panelContent: @Composable () -> Unit = { PanelHostContent(state, config, layout.panels, window.height, panel) }
    LaunchedEffect(config.banner) { config.banner?.offerCoachMark() }
    LaunchedEffect(config.banner, config.sheetEmpty) { config.banner?.refresh(config.sheetEmpty) }
    Box(modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize().testTag(ShellTags.layout(layout.placement)).trackInput(state), color = MaterialTheme.colorScheme.background) {
            if (layout.placement == StripPlacement.BottomDock) {
                Column(Modifier.fillMaxSize()) {
                    TopBar(state, config, focus, grouped)
                    BannerSlot(state, config, focus, grouped)
                    CanvasArea(state, config, focus, Modifier.weight(1f), canvas)
                    if (state.panelTab != null) panelContent()
                    strip(Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    strip(Modifier.fillMaxHeight())
                    Column(Modifier.weight(1f)) {
                        TopBar(state, config, focus, grouped)
                        BannerSlot(state, config, focus, grouped)
                        CanvasArea(state, config, focus, Modifier.weight(1f), canvas)
                    }
                    if (layout.panels == PanelHost.PersistentPane || state.panelTab != null) panelContent()
                }
            }
        }
        CoachWalkthroughOverlay(state.walkthrough, driver)
    }
}

/** Reports where the strip, the view cluster or the overflow button sit, so the walkthrough can point at them. */
private fun Modifier.anchorFor(state: SketchEditorState, step: WalkthroughStep): Modifier = onGloballyPositioned {
    val at = it.positionInRoot()
    state.walkthrough.anchors[step] = PlaceRect(at.x, at.y, at.x + it.size.width, at.y + it.size.height)
}

/**
 * Follows the input the user last used (hint and walkthrough texts depend on it) and lets Esc end the walkthrough from
 * anywhere once nothing nearer has used it: a menu, flyout or text overlay takes its own Esc first (ux.md section 17).
 */
@Composable
private fun Modifier.trackInput(state: SketchEditorState): Modifier = this
    .onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown) state.lastInput = InputKind.Keyboard
        false
    }
    .onKeyEvent { e ->
        val escape = e.type == KeyEventType.KeyDown && e.key == Key.Escape && state.walkthrough.active
        if (escape) state.walkthrough.end()
        escape
    }
    .pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val type = event.changes.firstOrNull()?.type ?: continue
                state.lastInput = if (type == PointerType.Touch || type == PointerType.Stylus) InputKind.TouchOrPen else InputKind.Mouse
            }
        }
    }

/** The one banner slot (ux.md section 1): the coach mark row today; each other row's own surface lands with its story. */
@Composable
private fun BannerSlot(state: SketchEditorState, config: SketchShellConfig, focus: ShellFocus, grouped: Boolean) {
    val banner = config.banner ?: return
    val head = banner.head ?: return
    if (head.row != BannerRowId.CoachMark) {
        Text(head.summary, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(ShellTags.BANNER))
        return
    }
    CoachMarkRow(
        head,
        onShowMe = {
            banner.dismissCoachMark(Dismissal.ActionButton)
            state.walkthrough.start(state.lastInput, grouped, WalkthroughState.CANVAS_FOCUS)
        },
        onGotIt = {
            banner.dismissCoachMark(Dismissal.Close)
            focus.restore(WalkthroughState.CANVAS_FOCUS)
        },
    )
}

@Composable
private fun TopBar(state: SketchEditorState, config: SketchShellConfig, focus: ShellFocus, grouped: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(48.dp).testTag(ShellTags.TOP_BAR).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(config.title, Modifier.weight(1f))
        Text(
            if (state.fingerDrawing) "Finger: On" else "Finger: Off",
            Modifier.testTag(ShellTags.FINGER_CHIP).clickable {
                state.fingerDrawing = !state.fingerDrawing
                config.onFingerDrawingChosen(state.fingerDrawing)
            }
                .size(width = 88.dp, height = TouchTargetFloor.floorDp(config.pointer).dp.coerceAtLeast(48.dp)),
        )
        Box {
            Text(
                "⋮",
                Modifier.testTag(ShellTags.OVERFLOW).size(48.dp).anchorFor(state, WalkthroughStep.Shortcuts).focusRequester(focus.overflow)
                    .clickable { state.walkthrough.anchorActivated(WalkthroughStep.Shortcuts); menu = true }
                    .semantics { contentDescription = "More options" },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                OverflowItems(state, config, grouped) { menu = false }
            }
        }
    }
}

@Composable
private fun OverflowItems(state: SketchEditorState, config: SketchShellConfig, grouped: Boolean, close: () -> Unit) {
    Column {
        DropdownMenuItem(
            text = { Text("Constrain (K)") },
            onClick = { close(); config.actions.onConstrain() },
            modifier = Modifier.testTag(ShellTags.OVERFLOW_CONSTRAIN),
        )
        DropdownMenuItem(
            text = { Text("Pan arrows") },
            onClick = { close(); state.panPadOpen = true },
            modifier = Modifier.testTag(ShellTags.OVERFLOW_PAN),
        )
        DropdownMenuItem(
            text = { Text(SketchStrings.Empty.SHORTCUTS) },
            onClick = { close(); config.onShortcuts() },
            modifier = Modifier.testTag(ShellTags.OVERFLOW_SHORTCUTS),
        )
        DropdownMenuItem(
            text = { Text(SketchStrings.Walkthrough.SHOW_TIPS) },
            onClick = {
                close()
                config.banner?.takeIf { it.head?.row == BannerRowId.CoachMark }?.dismissCoachMark(Dismissal.ActionButton)
                state.walkthrough.start(state.lastInput, grouped, OVERFLOW_FOCUS)
            },
            modifier = Modifier.testTag(ShellTags.OVERFLOW_TIPS),
        )
        for (tool in config.model.overflowTools) {
            DropdownMenuItem(
                text = { Text("More tools: ${tool.label}  ${tool.shortcut}") },
                onClick = { close(); state.chooseTool(tool.id) },
                modifier = Modifier.testTag(ShellTags.overflowTool(tool.id)),
            )
        }
    }
}

/**
 * The canvas with the overlays that must never meet the strip, the chip row or each other: the action chip row at the
 * bottom start and the view cluster at the bottom end, kept clear of the host's own bottom-end button by `endInset`.
 * The strip is outside this box, so it can never cover them.
 */
@Composable
private fun CanvasArea(state: SketchEditorState, config: SketchShellConfig, focus: ShellFocus, modifier: Modifier = Modifier, canvas: @Composable BoxScope.() -> Unit) {
    var canvasPx by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    Box(modifier.fillMaxWidth().testTag(ShellTags.CANVAS).onSizeChanged { canvasPx = it }.focusRequester(focus.canvas).focusable()) {
        canvas()
        if (config.banner?.emptyStateVisible == true) EmptyState(config.emptyActions, Modifier.align(Alignment.Center))
        Text(
            hintLine(state, config.pointer),
            Modifier.align(Alignment.TopStart).padding(8.dp).testTag(ShellTags.HINT),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 8.dp).testTag(ShellTags.CHIP_ROW),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.toolLock) {
                Text(
                    "Tool lock",
                    Modifier.testTag(ShellTags.TOOL_LOCK_CHIP).size(width = 88.dp, height = 44.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer).clickable { state.toolLock = false },
                )
            }
        }
        HelpPopupHost(state.help, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        val stripInset = PanPadPolicy.stripInsetDp(config.edgeStripAtEnd).dp
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp + config.endInset + stripInset, bottom = 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (state.panPadOpen) {
                val variant = PanPadPolicy.variant((canvasPx.width / density.density).toInt(), (canvasPx.height / density.density).toInt())
                PanPad(
                    variant,
                    onPan = { direction, quarters ->
                        val (dx, dy) = PanPadPolicy.contentShift(direction, quarters, canvasPx.width.toDouble(), canvasPx.height.toDouble())
                        state.viewport = state.viewport.panBy(dx, dy)
                    },
                    onFit = config.onFit,
                    onClose = { state.panPadOpen = false },
                )
            }
            ViewCluster(state, config, Modifier.anchorFor(state, WalkthroughStep.Zoom))
        }
    }
}

/** The tools that draw by dragging: with Finger drawing OFF and a touch pointer present they say how to draw with touch (UX-80). */
private val DRAG_TOOLS = setOf(ToolId.Rectangle, ToolId.Diamond, ToolId.Ellipse, ToolId.Arrow, ToolId.Freedraw, ToolId.Frame)

private fun hintLine(state: SketchEditorState, pointer: PointerClass): String {
    val touchHint = !state.fingerDrawing && pointer == PointerClass.TouchOrPen && state.activeTool in DRAG_TOOLS
    val base = if (touchHint) FingerDrawingController.TOOL_HINT_OFF else HINTS[state.activeTool].orEmpty()
    return if (state.toolLock) "Tool lock on. $base" else base
/** The hint of the active tool in the wording of the last input device: touch and pen name no key (UX-124). */
internal fun hintLine(state: SketchEditorState): String {
    val hint = SketchStrings.Hints.of(state.activeTool)
    val base = if (state.lastInput == InputKind.TouchOrPen) hint.touch else hint.keyboard
    return if (state.toolLock) "${SketchStrings.Hints.TOOL_LOCK_ON} $base" else base
}

/** Zoom out, zoom level, zoom in, grid and snap; every target is at least the floor of the pointer class. */
@Composable
private fun ViewCluster(state: SketchEditorState, config: SketchShellConfig, modifier: Modifier = Modifier) {
    val target = 44.dp
    var grid by remember { mutableStateOf(false) }
    var snap by remember { mutableStateOf(false) }
    // Activating a view button is what the zoom step points at, so it ends the walkthrough (ux.md section 22).
    val act = { action: () -> Unit -> { state.walkthrough.anchorActivated(WalkthroughStep.Zoom); action() } }
    Row(modifier.testTag(ShellTags.VIEW_CLUSTER), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ClusterButton("sketch.zoomout", "Zoom out", "−", target, act { state.viewport = state.viewport.zoomBy(centre, ZOOM_STEP_OUT) })
        ClusterButton("sketch.zoomlevel", "Zoom ${(state.viewport.zoom * PERCENT).toInt()}%", "${(state.viewport.zoom * PERCENT).toInt()}%", target, act { state.viewport = state.viewport.zoomTo(centre, 1.0) })
        ClusterButton("sketch.zoomin", "Zoom in", "+", target, act { state.viewport = state.viewport.zoomBy(centre, ZOOM_STEP_IN) })
        ClusterButton("sketch.grid", if (grid) "Grid on" else "Grid off", "#", target, act { grid = !grid })
        ClusterButton("sketch.snap", if (snap) "Snap on" else "Snap off", "⊙", target, act { snap = !snap })
        if (PanPadPolicy.buttonShown(config.panArrows, state.fingerDrawing, config.pointer == PointerClass.TouchOrPen)) {
            PanButton(state.panPadOpen, { state.panPadOpen = !state.panPadOpen })
        }
    }
}

@Composable
private fun ClusterButton(tag: String, description: String, glyph: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).testTag(tag).semantics { contentDescription = description }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph) }
}

private val centre = dev.stapler.stelekit.sketch.v2.view.ScreenPx(0.0, 0.0)
private const val ZOOM_STEP_IN = 1.25
private const val ZOOM_STEP_OUT = 0.8
private const val PERCENT = 100.0

@Composable
private fun PanelHostContent(state: SketchEditorState, config: SketchShellConfig, host: PanelHost, windowHeightDp: Int, panel: @Composable (PanelTab) -> Unit) {
    val tabs = PanelTab.entries.filter { it != PanelTab.Layers || config.hasMultipleLayers }
    val open = state.panelTab ?: PanelTab.Style
    val shape = when (host) {
        PanelHost.BottomSheet -> Modifier.fillMaxWidth().height(if (state.sheetSize == SheetSize.Full) (windowHeightDp * FULL_SHEET_SHARE).dp else PANEL_SHEET_HEIGHT)
        PanelHost.SideSheet, PanelHost.PersistentPane -> Modifier.fillMaxHeight().width(PANE_WIDTH)
    }
    Column(shape.testTag(ShellTags.PANEL).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (host == PanelHost.BottomSheet) PanelSheetHeader(state.sheetSize, { state.sheetSize = it }, { state.panelTab = null })
        Row(Modifier.fillMaxWidth()) {
            for (tab in tabs) {
                Text(
                    tab.name,
                    Modifier.testTag(ShellTags.tab(tab)).height(44.dp).clickable { state.panelTab = tab },
                    color = if (tab == open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        panel(open)
    }
}

private val PANEL_SHEET_HEIGHT = 240.dp
private const val FULL_SHEET_SHARE = 0.85f
private val PANE_WIDTH = 320.dp
