// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchChromeStrings
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuItem
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchDimensionStrings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.ui.sketch.CuePlacement
import dev.stapler.stelekit.ui.sketch.DurabilityCue
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.guide.TourController
import dev.stapler.stelekit.ui.sketch.v2.guide.TourOverlay
import dev.stapler.stelekit.ui.sketch.v2.guide.tourAnchor
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchGoToStrings
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchHostStrings
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings
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
    /** The one banner slot and the onboarding sequencer (Tasks 3.6.8b, 3.6.6c); null shows neither the coach mark nor the empty state. */
    val banner: BannerSlotState? = null,
    /** The sheet holds no element: the empty state may show (Task 3.6.6c). */
    val sheetEmpty: Boolean = false,
    val emptyActions: EmptyStateActions = EmptyStateActions(),
    /** Opens the shortcuts sheet (the overflow entry "Keyboard shortcuts"). */
    val onShortcuts: () -> Unit = {},
    /** The user toggled Finger drawing from the top-bar chip: the host stores it as an explicit choice (UX-80). */
    val onFingerDrawingChosen: (Boolean) -> Unit = {},
    /** The top bar's Back button; null hides it. */
    val onBack: (() -> Unit)? = null,
    val saveStatus: SaveStatus = SaveStatus.Saved,
    /** The compact-height Selection menu (UX-127): the items for the current selection and what each does. */
    val selectionItems: List<SelectionAction> = emptyList(),
    val onSelectionAction: (SelectionAction) -> Unit = {},
    /** What the banner row's primary button and More items do (Story 3.1.4). */
    val bannerActions: BannerActions = BannerActions(),
    /** The toast lane's queue; null shows no toasts (Story 3.1.4c). */
    val toasts: ToastLaneHost? = null,
    /** False when the target's solver tier is `Hidden`: the Constraints tab and the Constrain entries are not shown (ADR-003). */
    val constraintsAvailable: Boolean = true,
    /** The sheet's auto-constrain setting; its toggle joins the overflow menu and its "Free draw" chip the chip row (Story 4.2.2c). */
    val autoConstrain: AutoConstrainState? = null,
    /** The touch magnifier's switch in the overflow menu; null hides it. */
    val loupe: LoupePreference? = null,
    /** The length display switch of a technical sheet in the overflow menu; null hides it. */
    val lengthUnit: LengthUnitState? = null,
    /** The overflow entries "Import .excalidraw..." and "Export..." (Stories 5.1.5, 5.2.3); null hides the entry. */
    val onImport: (() -> Unit)? = null,
    /** Why Import cannot run here (no file storage, UX-135): the entry is then shown disabled with this text. */
    val importDisabledReason: String? = null,
    val onExport: (() -> Unit)? = null,
    /** The overflow entry "Go to element..." (Story 3.6.5); null hides it, the host opens [GoToElementDialog]. */
    val onGoToElement: (() -> Unit)? = null,
    /** The rows of the one Esc order the host owns right now (a dialog, "Solving...", a picker, a mid-draw preview, Crosshair mode). */
    val escRows: () -> Set<EscRow> = { emptySet() },
    /** A key pressed while the canvas has focus; true when the host used it. */
    val onCanvasKey: (KeyEvent) -> Boolean = { false },
    /** Chips the host adds after the shell's own (the Cancel chip of a slow solve). */
    val extraChips: List<ActionChip> = emptyList(),
    /** Chips that lead the row, ahead of the shell's own, so the most-used ones are first in thumb reach (the technical sheet's shortcuts). */
    val priorityChips: List<ActionChip> = emptyList(),
    /** The view cluster's grid and snap toggles live in the host when [onGrid] and [onSnap] are set; the cluster keeps its own state otherwise. */
    val grid: Boolean = false,
    val snap: Boolean = false,
    val onGrid: (() -> Unit)? = null,
    val onSnap: (() -> Unit)? = null,
    /** Told when a finger rests on the Snap button (true) and when it lifts (false): the touch way to skip snapping for one drag; null disables it. */
    val onSnapHold: ((Boolean) -> Unit)? = null,
    val snapHeld: Boolean = false,
    /** The overflow entry "Restore original (v1) sheet"; null hides it (no restore action wired). */
    val restore: RestoreMenuEntry? = null,
    /** The persistent cue of a suspected private window or a temporary graph (UX-153); null shows none. */
    val durabilityCue: DurabilityCueView? = null,
    /** True for a technical sheet: the Technical tab (named dimensions and zones) is listed. */
    val technicalPanel: Boolean = false,
    /** Applied to the canvas region: Tab, F6 and Esc through the canvas stops ([canvasRegionKeys]). */
    val canvasModifier: Modifier = Modifier,
    /** Told once the shell has its regions, so the host can move focus between them. */
    val onRegions: (RegionFocus) -> Unit = {},
    /** The sheet type, when it can be switched from the menu (a diagram or technical sheet that is not read-only); null hides the switch. */
    val sheetKind: dev.stapler.stelekit.sketch.v2.constraints.SheetKind? = null,
    val onSheetKind: ((dev.stapler.stelekit.sketch.v2.constraints.SheetKind) -> Unit)? = null,
    /** The feature tours; null shows none. */
    val tours: TourController? = null,
    /** Opens Help and search from the top bar and the menu; null hides both. */
    val onHelp: (() -> Unit)? = null,
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
    const val OVERFLOW_PANEL_STYLE = "sketch.overflow.panel.style"
    const val OVERFLOW_PANEL_ELEMENTS = "sketch.overflow.panel.elements"
    const val OVERFLOW_PANEL_LAYERS = "sketch.overflow.panel.layers"
    const val OVERFLOW_PANEL_TECHNICAL = "sketch.overflow.panel.technical"
    const val OVERFLOW_MAKE_TECHNICAL = "sketch.overflow.make.technical"
    const val OVERFLOW_MAKE_DIAGRAM = "sketch.overflow.make.diagram"
    const val OVERFLOW_SHORTCUTS = "sketch.overflow.shortcuts"
    const val OVERFLOW_TIPS = "sketch.overflow.tips"
    const val OVERFLOW_HELP = "sketch.overflow.help"
    const val OVERFLOW_IMPORT = "sketch.overflow.import"
    const val OVERFLOW_EXPORT = "sketch.overflow.export"
    const val OVERFLOW_RESTORE = "sketch.overflow.restore"
    const val OVERFLOW_GO_TO = "sketch.overflow.goto"
    const val BANNER = "sketch.banner"
    const val HINT = "sketch.hint"
    const val DURABILITY_CUE = "sketch.cue.durability"
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
    val extent = ChromeBudget.stripExtentDp(layout.placement, window.width, window.height)
    val fontScale = LocalDensity.current.fontScale.toDouble()
    val plan = remember(window, fontScale) { ChromeBudget.plan(window.width, window.height, fontScale, config.model.stripTools.size) }
    val hintShown = ChromeRow.HintStatus in ChromeBudget.presentRows(layout) && ChromeRow.HintStatus !in plan.collapsed
    val cue = remember(config.durabilityCue, window, fontScale) { config.durabilityCue?.let { DurabilityCueHost.shown(it, window.width, window.height, fontScale) } }
    val focus = remember { ShellFocus(FocusRequester(), FocusRequester()) }
    val grouped = !config.model.isFlat(extent)
    val regions = remember { RegionFocus(mapOf(TabStop.Canvas to focus.canvas, TabStop.TopBar to focus.overflow)) }
    SideEffect { config.onRegions(regions) }
    val driver = remember(extent, config.model, config.escRows) {
        WalkthroughDriver({ state.lastInput }, { grouped }, focus::restore, regions) { ShellEscape.walkthroughActs(state, config.escRows()) }
    }
    val strip: @Composable (Modifier) -> Unit = { m ->
        val anchored = m.anchorFor(state, WalkthroughStep.Tools).tourAnchored(config, TabStop.ToolStrip).region(regions, TabStop.ToolStrip, exitToPopover(state, WalkthroughStep.Tools))
        ToolStrip(config.model, state, layout.placement, extent, config.pointer, config.status, config.actions, anchored)
    }
    val panelContent: @Composable () -> Unit = { PanelHostContent(state, config, layout.panels, window.height, panel) }
    LaunchedEffect(config.banner) { config.banner?.offerCoachMark() }
    LaunchedEffect(config.banner, config.sheetEmpty) { config.banner?.refresh(config.sheetEmpty) }
    Box(modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize().testTag(ShellTags.layout(layout.placement)).trackInput(state, driver, config.escRows), color = MaterialTheme.colorScheme.background) {
            if (layout.placement == StripPlacement.BottomDock) {
                Column(Modifier.fillMaxSize()) {
                    ShellTopBar(state, config, focus, grouped, window, regions)
                    BannerSlot(state, config, focus, grouped)
                    CanvasArea(state, config, focus, regions, Modifier.weight(1f).tourAnchored(config, TabStop.Canvas), hintShown, cue, helpNearStrip = true, canvas = canvas)
                    if (state.panelTab != null) panelContent()
                    strip(Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    strip(Modifier.fillMaxHeight())
                    Column(Modifier.weight(1f)) {
                        ShellTopBar(state, config, focus, grouped, window, regions)
                        BannerSlot(state, config, focus, grouped)
                        CanvasArea(state, config, focus, regions, Modifier.weight(1f).tourAnchored(config, TabStop.Canvas), hintShown, cue, canvas = canvas)
                    }
                    if (layout.panels == PanelHost.PersistentPane || state.panelTab != null) panelContent()
                }
            }
        }
        CoachWalkthroughOverlay(state.walkthrough, driver)
        config.tours?.let { TourOverlay(it) }
    }
}

/** Tab out of an anchor region goes to the walkthrough popover while that anchor's step is showing (ux.md section 22). */
@OptIn(ExperimentalComposeUiApi::class)
private fun exitToPopover(state: SketchEditorState, step: WalkthroughStep): (FocusDirection) -> FocusRequester = { direction ->
    if (direction == FocusDirection.Next && state.walkthrough.step == step) state.walkthrough.popoverFocus else FocusRequester.Default
}

/** Reports where [stop] sits to the feature tours, when the host runs them. */
private fun Modifier.tourAnchored(config: SketchShellConfig, stop: TabStop): Modifier = config.tours?.let { tourAnchor(it, stop) } ?: this

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
private fun Modifier.trackInput(state: SketchEditorState, driver: WalkthroughDriver, hostRows: () -> Set<EscRow>): Modifier = this
    .onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown) state.lastInput = InputKind.Keyboard
        false
    }
    .onKeyEvent { e ->
        e.type == KeyEventType.KeyDown && e.key == Key.Escape && ShellEscape.press(state, hostRows()) { driver.onEnded(state.walkthrough.end()) }
    }
    .pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val type = event.changes.firstOrNull()?.type ?: continue
                state.lastInput = if (type == PointerType.Touch || type == PointerType.Stylus) InputKind.TouchOrPen else InputKind.Mouse
                // A press anywhere closes a help popup a touch opened (it would otherwise sit over the drawing until Close).
                if (event.type == PointerEventType.Press) state.help.dismissTouchOpened()
            }
        }
    }

/** The one banner slot (ux.md section 1): the coach mark row today; each other row's own surface lands with its story. */
@Composable
private fun BannerSlot(state: SketchEditorState, config: SketchShellConfig, focus: ShellFocus, grouped: Boolean) {
    val banner = config.banner ?: return
    val head = banner.head ?: return
    if (head.row != BannerRowId.CoachMark) {
        BannerSlotContent(head, config.bannerActions, Modifier.testTag(ShellTags.BANNER))
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
private fun ShellTopBar(state: SketchEditorState, config: SketchShellConfig, focus: ShellFocus, grouped: Boolean, window: WindowDp, regions: RegionFocus) {
    val fontScale = LocalDensity.current.fontScale.toDouble()
    val plan = ChromeBudget.topBar(ShellLayout.choose(window.width, window.height), window.width, fontScale)
    TopBar(
        TopBarContent(config.title, plan, state.fingerDrawing, config.pointer, config.saveStatus, state.selection.size, config.selectionItems),
        TopBarActions(
            onBack = config.onBack,
            onFingerToggle = {
                state.fingerDrawing = !state.fingerDrawing
                config.onFingerDrawingChosen(state.fingerDrawing)
            },
            onSelectionAction = config.onSelectionAction,
            onHelp = config.onHelp,
        ),
        Modifier.padding(horizontal = 4.dp),
        Modifier.anchorFor(state, WalkthroughStep.Shortcuts).tourAnchored(config, TabStop.TopBar)
            .focusProperties { next = if (state.walkthrough.step == WalkthroughStep.Shortcuts) state.walkthrough.popoverFocus else FocusRequester.Default }
            .focusRequester(focus.overflow)
            .mark(regions, TabStop.TopBar),
        onOverflowOpen = { state.walkthrough.anchorActivated(WalkthroughStep.Shortcuts) },
    ) { close -> OverflowItems(state, config, grouped, close) }
}

/** Opens a side panel tab or switches the sheet type; a phone's panels have no other entry. */
@Composable
private fun PanelMenuItems(state: SketchEditorState, config: SketchShellConfig, close: () -> Unit) {
    fun open(tab: PanelTab) { close(); state.panelTab = tab }
    Column {
        MenuSection(SketchChromeStrings.MENU_PANELS)
        // One row of chips, not one row per panel: the menu stays short enough to reach its last entries on a small phone.
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuChip(SketchStrings.PanelTabs.STYLE, ShellTags.OVERFLOW_PANEL_STYLE) { open(PanelTab.Style) }
            MenuChip(SketchStrings.PanelTabs.ELEMENTS, ShellTags.OVERFLOW_PANEL_ELEMENTS) { open(PanelTab.Elements) }
            if (config.hasMultipleLayers) MenuChip(SketchStrings.PanelTabs.LAYERS, ShellTags.OVERFLOW_PANEL_LAYERS) { open(PanelTab.Layers) }
            if (config.technicalPanel) MenuChip(SketchStrings.PanelTabs.TECHNICAL, ShellTags.OVERFLOW_PANEL_TECHNICAL) { open(PanelTab.Technical) }
        }
    }
}

/** A pill-shaped menu entry that opens a panel; 48 dp tall like the rows around it. */
@Composable
private fun MenuChip(label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.height(MENU_CHIP_HEIGHT).testTag(tag).clip(RoundedCornerShape(MENU_CHIP_HEIGHT / 2)).background(MaterialTheme.colorScheme.secondaryContainer)
            .semantics { role = Role.Button }.clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
}

private val MENU_CHIP_HEIGHT = 48.dp
private val MENU_TEXT_INSET = 12.dp

/** The sheet's own switches: its type (diagram or technical) and the constraint entries that only a technical sheet makes useful. */
@Composable
private fun SheetMenuItems(state: SketchEditorState, config: SketchShellConfig, close: () -> Unit) {
    Column {
        MenuSection(SketchChromeStrings.MENU_SHEET)
        val switch = config.onSheetKind
        when (config.sheetKind) {
            dev.stapler.stelekit.sketch.v2.constraints.SheetKind.Diagram -> if (switch != null) DropdownMenuItem(
                leadingIcon = { StripIconView(StripIcon.Technical, MaterialTheme.colorScheme.onSurface) },
                text = { Text(SketchStrings.PanelMenu.MAKE_TECHNICAL) },
                onClick = { close(); switch(dev.stapler.stelekit.sketch.v2.constraints.SheetKind.Technical); state.panelTab = PanelTab.Technical },
                modifier = Modifier.testTag(ShellTags.OVERFLOW_MAKE_TECHNICAL),
            )
            dev.stapler.stelekit.sketch.v2.constraints.SheetKind.Technical -> if (switch != null) DropdownMenuItem(
                leadingIcon = { StripIconView(StripIcon.Rectangle, MaterialTheme.colorScheme.onSurface) },
                text = { Text(SketchStrings.PanelMenu.MAKE_DIAGRAM) },
                onClick = { close(); switch(dev.stapler.stelekit.sketch.v2.constraints.SheetKind.Diagram) },
                modifier = Modifier.testTag(ShellTags.OVERFLOW_MAKE_DIAGRAM),
            )
            else -> Unit
        }
        if (config.constraintsAvailable) {
            DropdownMenuItem(
                leadingIcon = { StripIconView(StripIcon.Constrain, MaterialTheme.colorScheme.onSurface) },
                text = { Text(SketchChromeStrings.CONSTRAIN) },
                onClick = { close(); config.actions.onConstrain() },
                modifier = Modifier.testTag(ShellTags.OVERFLOW_CONSTRAIN),
            )
        }
        config.autoConstrain?.takeIf { config.constraintsAvailable }?.let { AutoConstrainToggle(it, Modifier.padding(horizontal = MENU_TEXT_INSET)) }
        config.loupe?.let { LoupeToggle(it, Modifier.padding(horizontal = MENU_TEXT_INSET)) }
        config.lengthUnit?.let { units ->
            val current = units.format
            DropdownMenuItem(
                text = { Text(SketchDimensionStrings.Units.menuText(current.displayName)) },
                // The menu stays open so the switch can be tapped through to the display wanted.
                onClick = units::cycle,
                modifier = Modifier.testTag(LengthUnitTags.MENU_ROW)
                    .semantics { contentDescription = SketchDimensionStrings.Units.menuDescription(current.displayName, current.next().displayName); role = Role.Button },
            )
        }
    }
}

/** A small heading that groups the entries under it, announced as a heading by a screen reader. */
@Composable
private fun MenuSection(label: String) {
    Text(
        label,
        Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * The three-dot menu, in sections so a long list can be scanned: Panels, Sheet, Import and export, Tools, View, Help. A phone has
 * no other way to open the panels, so they come first; the rarely used view and help entries come last.
 */
@Composable
private fun OverflowItems(state: SketchEditorState, config: SketchShellConfig, grouped: Boolean, close: () -> Unit) {
    Column {
        PanelMenuItems(state, config, close)
        SheetMenuItems(state, config, close)
        FileMenuItems(config, close)
        if (config.model.overflowTools.isNotEmpty()) {
            MenuSection(SketchChromeStrings.MENU_TOOLS)
            for (tool in config.model.overflowTools) {
                DropdownMenuItem(
                    leadingIcon = { StripIconView(StripIcon.of(tool.id), MaterialTheme.colorScheme.onSurface) },
                    text = { Text(SketchChromeStrings.moreTool(tool.label, tool.shortcut)) },
                    onClick = { close(); state.chooseTool(tool.id) },
                    modifier = Modifier.testTag(ShellTags.overflowTool(tool.id)),
                )
            }
        }
        MenuSection(SketchChromeStrings.MENU_VIEW)
        DropdownMenuItem(
            text = { Text(SketchChromeStrings.PAN_ARROWS) },
            onClick = { close(); state.panPadOpen = true },
            modifier = Modifier.testTag(ShellTags.OVERFLOW_PAN),
        )
        config.onGoToElement?.let { onGoTo ->
            DropdownMenuItem(
                text = { Text(SketchGoToStrings.MENU_ITEM) },
                onClick = { close(); onGoTo() },
                modifier = Modifier.testTag(ShellTags.OVERFLOW_GO_TO),
            )
        }
        DropdownMenuItem(
            text = { Text(SketchStrings.Empty.SHORTCUTS) },
            onClick = { close(); config.onShortcuts() },
            modifier = Modifier.testTag(ShellTags.OVERFLOW_SHORTCUTS),
        )
        MenuSection(SketchChromeStrings.MENU_HELP)
        config.onHelp?.let { onHelp ->
            DropdownMenuItem(
                text = { Text(SketchStrings.TopBar.HELP) },
                onClick = { close(); onHelp() },
                modifier = Modifier.testTag(ShellTags.OVERFLOW_HELP),
            )
        }
        DropdownMenuItem(
            text = { Text(SketchStrings.Walkthrough.SHOW_TIPS) },
            onClick = {
                close()
                config.banner?.takeIf { it.head?.row == BannerRowId.CoachMark }?.dismissCoachMark(Dismissal.ActionButton)
                state.walkthrough.start(state.lastInput, grouped, OVERFLOW_FOCUS)
            },
            modifier = Modifier.testTag(ShellTags.OVERFLOW_TIPS),
        )
    }
}

/** Import, export and restore: the entries that leave or replace the sketch's file, kept above the view and help entries so they stay in reach on a small phone. */
@Composable
private fun FileMenuItems(config: SketchShellConfig, close: () -> Unit) {
    Column {
        if (config.onImport != null || config.onExport != null || config.restore != null) MenuSection(SketchChromeStrings.MENU_FILE)
        config.onImport?.let { onImport ->
            DropdownMenuItem(
                text = { ImportMenuLabel(config.importDisabledReason) },
                onClick = { close(); onImport() },
                enabled = config.importDisabledReason == null,
                modifier = Modifier.testTag(ShellTags.OVERFLOW_IMPORT),
            )
        }
        config.onExport?.let { onExport ->
            DropdownMenuItem(
                text = { Text(SketchImportExportStrings.Export.MENU_ITEM) },
                onClick = { close(); onExport() },
                modifier = Modifier.testTag(ShellTags.OVERFLOW_EXPORT),
            )
        }
        config.restore?.let { RestoreMenuItem(it, close) }
    }
}

/** "Restore original (v1) sheet": hidden without a backup, shown disabled with its reason where it cannot run (UX-90, UX-137). */
@Composable
private fun RestoreMenuItem(entry: RestoreMenuEntry, close: () -> Unit) {
    val availability = entry.availability
    if (availability == RestoreAvailability.Hidden) return
    DropdownMenuItem(
        text = {
            Column {
                Text(RestoreCopy.ACTION)
                (availability as? RestoreAvailability.Disabled)?.let { Text(it.reason, style = MaterialTheme.typography.bodySmall) }
            }
        },
        onClick = { close(); entry.onRestore() },
        enabled = availability == RestoreAvailability.Enabled,
        modifier = Modifier.testTag(ShellTags.OVERFLOW_RESTORE),
    )
}

/** "Import .excalidraw..." with, where it is disabled, the reason beneath it (UX-135): the reason is visible text, not a tooltip. */
@Composable
private fun ImportMenuLabel(disabledReason: String?) {
    Column {
        Text(SketchImportExportStrings.Import.MENU_ITEM)
        disabledReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * The canvas with the overlays that must never meet the strip, the chip row or each other: the action chip row at the
 * bottom start and the view cluster at the bottom end, kept clear of the host's own bottom-end button by `endInset`.
 * The strip is outside this box, so it can never cover them.
 */
@Composable
private fun CanvasArea(
    state: SketchEditorState,
    config: SketchShellConfig,
    focus: ShellFocus,
    regions: RegionFocus,
    modifier: Modifier = Modifier,
    hintShown: Boolean = true,
    cue: ShownCue? = null,
    helpNearStrip: Boolean = false,
    canvas: @Composable BoxScope.() -> Unit,
) {
    var canvasPx by remember { mutableStateOf(IntSize.Zero) }
    // Where the bottom-end stack (view cluster, pan pad) starts, measured, so the help popup sits just above it however tall it is.
    var overlayTopPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    Box(modifier.fillMaxWidth().testTag(ShellTags.CANVAS).onSizeChanged { canvasPx = it }.focusRequester(focus.canvas).mark(regions, TabStop.Canvas).focusable().then(config.canvasModifier).onKeyEvent(config.onCanvasKey)) {
        canvas()
        if (config.banner?.emptyStateVisible == true) EmptyState(config.emptyActions, Modifier.align(Alignment.Center))
        Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            if (hintShown && (state.selection.isEmpty() || cue != null)) Text(cueHint(cue, hintLine(state, config.pointer), canvasPx.width / density.density), Modifier.testTag(ShellTags.HINT).background(MaterialTheme.colorScheme.surface.copy(alpha = HINT_SCRIM_ALPHA), RoundedCornerShape(HINT_RADIUS)).padding(horizontal = 8.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Above the chip row, not under the hint: the top of the canvas is where a finger drags and a toast there hides the shape being moved.
        val chipsRaised = canvasPx.width / density.density < CHIPS_BESIDE_CLUSTER_MIN_DP
        config.toasts?.let {
            ToastLaneOverlay(it, canvasPx.height / density.density, Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 16.dp + config.endInset + PanPadPolicy.stripInsetDp(config.edgeStripAtEnd).dp, bottom = (if (chipsRaised) CHIPS_RAISED else 8.dp) + TOAST_ABOVE_CHIPS))
        }
        ActionChipRow(
            config.priorityChips + listOfNotNull(
                cue?.takeIf { it.placement == CuePlacement.ChipRow }?.let { ActionChip(ShellTags.DURABILITY_CUE, it.chip?.text.orEmpty(), it.view.accessibleName, onClick = it.view.onActivate) },
                if (state.toolLock) ActionChip(ShellTags.TOOL_LOCK_CHIP, SketchStrings.Chrome.TOOL_LOCK_CHIP, icon = StripIcon.LockClosed) { state.toolLock = false } else null,
                config.autoConstrain?.takeIf { config.constraintsAvailable }?.let(AutoConstrainChips::freeDraw),
            ) + config.extraChips,
            // On a narrow canvas the chips and the view cluster cannot share the bottom row, so the chips sit on the row above it.
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = if (canvasPx.width / density.density < CHIPS_BESIDE_CLUSTER_MIN_DP) CHIPS_RAISED else 8.dp),
        )
        // The popup sits beside the control it explains: above the dock when the strip is at the bottom, at the top otherwise.
        val aboveOverlays = with(density) { (canvasPx.height - overlayTopPx).coerceAtLeast(0f).toDp() } + HELP_GAP + if (state.selection.isNotEmpty()) HELP_CLEARS_ZOOM_CHIP else 0.dp
        if (helpNearStrip) HelpPopupHost(state.help, Modifier.align(Alignment.BottomCenter).padding(bottom = aboveOverlays))
        else HelpPopupHost(state.help, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        val stripInset = PanPadPolicy.stripInsetDp(config.edgeStripAtEnd).dp
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp + config.endInset + stripInset, bottom = 8.dp)
                .onGloballyPositioned { overlayTopPx = it.positionInParent().y },
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
            ViewCluster(state, config, Modifier.anchorFor(state, WalkthroughStep.Zoom).region(regions, TabStop.ViewCluster, exitToPopover(state, WalkthroughStep.Zoom)))
        }
    }
}

/** The hint sits on a soft panel so a drawn line behind it never runs through the words. */
private const val HINT_SCRIM_ALPHA = 0.85f
private val HINT_RADIUS = 8.dp

private fun cueHint(cue: ShownCue?, hint: String, widthDp: Float): String =
    DurabilityCueHost.hintText(cue, hint, DurabilityCue.charsThatFit(widthDp.toInt(), 1.0))

/** The tools that draw by dragging: with Finger drawing OFF and a touch pointer present they say how to draw with touch (UX-80). */
private val DRAG_TOOLS = setOf(ToolId.Rectangle, ToolId.Diamond, ToolId.Ellipse, ToolId.Arrow, ToolId.Freedraw, ToolId.Frame)

/** The hint of the active tool in the wording of the last input device: touch and pen name no key (UX-124). */
internal fun hintLine(state: SketchEditorState, pointer: PointerClass = PointerClass.MouseOnly): String {
    val hint = SketchStrings.Hints.of(state.activeTool)
    val touchHint = !state.fingerDrawing && pointer == PointerClass.TouchOrPen && state.activeTool in DRAG_TOOLS
    val base = when {
        touchHint -> FingerDrawingController.TOOL_HINT_OFF
        state.lastInput == InputKind.TouchOrPen -> hint.touch
        else -> hint.keyboard
    }
    return if (state.toolLock) "${SketchStrings.Hints.TOOL_LOCK_ON} $base" else base
}

/** Zoom out, zoom level, zoom in, grid and snap; every target is at least the floor of the pointer class. */
@Composable
private fun ViewCluster(state: SketchEditorState, config: SketchShellConfig, modifier: Modifier = Modifier) {
    val target = ChromeBudget.TOOL_TARGET_DP.dp
    var localGrid by remember { mutableStateOf(false) }
    var localSnap by remember { mutableStateOf(false) }
    val grid = if (config.onGrid != null) config.grid else localGrid
    val snap = if (config.onSnap != null) config.snap else localSnap
    // Activating a view button is what the zoom step points at, so it ends the walkthrough (ux.md section 22).
    val act = { action: () -> Unit -> { state.walkthrough.anchorActivated(WalkthroughStep.Zoom); action() } }
    // Read through derivedStateOf so a pinch recomposes the cluster only when the shown percentage changes, not on every frame.
    val percent by remember(state) { derivedStateOf { (state.viewport.zoom * PERCENT).toInt() } }
    // A soft pill keeps the controls readable over any drawing; Grid and Snap show their on/off state as a filled chip.
    Row(
        modifier.testTag(ShellTags.VIEW_CLUSTER).clip(RoundedCornerShape(CLUSTER_RADIUS)).background(MaterialTheme.colorScheme.surface.copy(alpha = CLUSTER_ALPHA)),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val zoomOut = act { state.viewport = state.viewport.zoomBy(centre, ZOOM_STEP_OUT) }
        val zoomIn = act { state.viewport = state.viewport.zoomBy(centre, ZOOM_STEP_IN) }
        ClusterButton(state, "sketch.zoomout", SketchChromeStrings.ZOOM_OUT, SketchChromeStrings.ZOOM_OUT_HINT, StripIcon.ZoomOut, null, target, null, zoomOut)
        ClusterButton(state, "sketch.zoomlevel", SketchChromeStrings.zoom(percent), SketchChromeStrings.ZOOM_LEVEL_HINT, null, "$percent%", target, null, act { state.viewport = state.viewport.zoomTo(centre, 1.0) })
        ClusterButton(state, "sketch.zoomin", SketchChromeStrings.ZOOM_IN, SketchChromeStrings.ZOOM_IN_HINT, StripIcon.ZoomIn, null, target, null, zoomIn)
        ClusterButton(state, "sketch.grid", if (grid) SketchHostStrings.View.GRID_ON else SketchHostStrings.View.GRID_OFF, SketchChromeStrings.GRID_HINT, StripIcon.Grid, null, target, grid, act { config.onGrid?.invoke() ?: run { localGrid = !localGrid } })
        // A finger resting here pauses snapping (the touch counterpart of Ctrl), so a second finger can drag while this one holds.
        val held = config.snapHeld
        val snapName = when {
            held -> SketchHostStrings.View.SNAP_PAUSED
            snap -> SketchHostStrings.View.SNAP_ON
            else -> SketchHostStrings.View.SNAP_OFF
        }
        ClusterButton(state, "sketch.snap", snapName, SketchChromeStrings.SNAP_HINT, StripIcon.Snap, null, target, snap && !held, act { config.onSnap?.invoke() ?: run { localSnap = !localSnap } }, config.onSnapHold)
        if (PanPadPolicy.buttonShown(config.panArrows, state.fingerDrawing, config.pointer == PointerClass.TouchOrPen)) {
            PanButton(state.panPadOpen, { state.panPadOpen = !state.panPadOpen })
        }
    }
}

/**
 * One view-cluster button: an [icon] or a [label], with the hint popup on long press, focus and hover. [on] is null for a plain
 * action and true or false for a toggle, which then reads as a filled chip and announces its state.
 */
@Composable
private fun ClusterButton(
    state: SketchEditorState, tag: String, description: String, hint: String, icon: StripIcon?, label: String?, size: Dp, on: Boolean?, onClick: () -> Unit,
    onHold: ((Boolean) -> Unit)? = null,
) {
    val entry = HelpEntry(tag, description, hint)
    val scheme = MaterialTheme.colorScheme
    // A label may be wider than the target at a large font, so the target grows with it instead of clipping the text.
    Box(
        Modifier.height(size).defaultMinSize(minWidth = size).testTag(tag)
            .helpOnFocus(state.help, entry).helpOnHover(state.help, entry)
            .holdReporting(onHold)
            .semantics { contentDescription = description; if (on != null) { role = Role.Switch; toggleableState = ToggleableState(on) } }
            .combinedClickable(onLongClick = { state.help.show(entry) }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxHeight().defaultMinSize(minWidth = size - CLUSTER_INSET * 2).padding(vertical = CLUSTER_INSET).clip(RoundedCornerShape(CLUSTER_RADIUS))
                .background(if (on == true) scheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                .padding(horizontal = if (label != null) CLUSTER_LABEL_PAD else 0.dp),
            contentAlignment = Alignment.Center,
        ) {
            val ink = if (on == true) scheme.onPrimaryContainer else scheme.onSurface
            if (icon != null) StripIconView(icon, ink, size = CLUSTER_ICON)
            if (label != null) Text(label, color = ink, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

private val CLUSTER_RADIUS = 16.dp
private val CLUSTER_INSET = 4.dp
private val CLUSTER_ICON = 22.dp
private val CLUSTER_LABEL_PAD = 6.dp
private val HELP_GAP = 8.dp
private val HELP_CLEARS_ZOOM_CHIP = 56.dp
private val CHIPS_RAISED = 64.dp
private val TOAST_ABOVE_CHIPS = ChromeBudget.TOOL_TARGET_DP.dp + 8.dp
private const val CHIPS_BESIDE_CLUSTER_MIN_DP = 420f
private const val CLUSTER_ALPHA = 0.92f

private val centre = dev.stapler.stelekit.sketch.v2.view.ScreenPx(0.0, 0.0)
private const val ZOOM_STEP_IN = 1.25
private const val ZOOM_STEP_OUT = 0.8
private const val PERCENT = 100.0

@Composable
private fun PanelHostContent(state: SketchEditorState, config: SketchShellConfig, host: PanelHost, windowHeightDp: Int, panel: @Composable (PanelTab) -> Unit) {
    val tabs = PanelTab.entries.filter {
        (it != PanelTab.Layers || config.hasMultipleLayers) && (it != PanelTab.Constraints || config.constraintsAvailable) && (it != PanelTab.Technical || config.technicalPanel)
    }
    // A tab that left the list (the sheet turned into a diagram) falls back to Style instead of leaving its panel orphaned.
    val open = (state.panelTab ?: PanelTab.Style).takeIf { it in tabs } ?: PanelTab.Style
    val shape = when (host) {
        PanelHost.BottomSheet -> Modifier.fillMaxWidth().height(if (state.sheetSize == SheetSize.Full) (windowHeightDp * FULL_SHEET_SHARE).dp else PANEL_SHEET_HEIGHT)
        PanelHost.SideSheet, PanelHost.PersistentPane -> Modifier.fillMaxHeight().width(PANE_WIDTH)
    }
    Column(shape.tourAnchored(config, TabStop.SidePane).testTag(ShellTags.PANEL).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (host == PanelHost.BottomSheet) PanelSheetHeader(state.sheetSize, { state.sheetSize = it }, { state.panelTab = null })
        // The tabs scroll sideways when they do not fit, each is a 48 dp target with room around its label, and the open one is underlined.
        val tabScroll = rememberScrollState()
        val surface = MaterialTheme.colorScheme.surfaceVariant
        // The cue sits in a gutter beside the labels, not on them: the tabs are padded in by the gutter and the chevron is drawn in it.
        Row(
            Modifier.fillMaxWidth().scrollEdgeFade(tabScroll, false, surface, MaterialTheme.colorScheme.onSurfaceVariant, gutter = TAB_GUTTER)
                .padding(horizontal = TAB_GUTTER).horizontalScroll(tabScroll).selectableGroup().semantics { contentDescription = SketchStrings.PanelTabs.LIST },
        ) {
            for (tab in tabs) PanelTabButton(tab, tab == open) { state.panelTab = tab }
        }
        panel(open)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PanelTabButton(tab: PanelTab, open: Boolean, onSelect: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // The open tab is scrolled into view, so opening "Technical" from the menu never leaves it off the edge with no tab shown as open.
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(open) { if (open) runCatching { bring.bringIntoView() } }
    Box(
        Modifier.testTag(ShellTags.tab(tab)).bringIntoViewRequester(bring).defaultMinSize(minWidth = TAB_MIN_WIDTH, minHeight = TAB_HEIGHT).selectable(selected = open, role = Role.Tab, onClick = onSelect)
            .drawBehind { if (open) drawRect(scheme.primary, Offset(0f, size.height - TAB_UNDERLINE.toPx()), Size(size.width, TAB_UNDERLINE.toPx())) },
        contentAlignment = Alignment.Center,
    ) {
        // The padding is on the label, inside the target, so the whole padded tab is the press area; the open tab is underlined, not only recoloured.
        Text(tabLabel(tab), Modifier.padding(horizontal = TAB_PAD), color = if (open) scheme.primary else scheme.onSurface, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

private val TAB_HEIGHT = 48.dp
private val TAB_MIN_WIDTH = 64.dp
private val TAB_GUTTER = 16.dp
private val TAB_PAD = 16.dp
private val TAB_UNDERLINE = 3.dp

private fun tabLabel(tab: PanelTab): String = when (tab) {
    PanelTab.Style -> SketchStrings.PanelTabs.STYLE
    PanelTab.Layers -> SketchStrings.PanelTabs.LAYERS
    PanelTab.Constraints -> SketchStrings.PanelTabs.CONSTRAINTS
    PanelTab.Elements -> SketchStrings.PanelTabs.ELEMENTS
    PanelTab.Library -> SketchStrings.PanelTabs.LIBRARY
    PanelTab.Technical -> SketchStrings.PanelTabs.TECHNICAL
}

private val PANEL_SHEET_HEIGHT = 240.dp
private const val FULL_SHEET_SHARE = 0.85f
private val PANE_WIDTH = 320.dp
