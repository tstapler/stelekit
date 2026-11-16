// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.tools.ToolId

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
    val strip: @Composable (Modifier) -> Unit = { m ->
        ToolStrip(config.model, state, layout.placement, extent, config.pointer, config.status, config.actions, m)
    }
    val panelContent: @Composable () -> Unit = { PanelHostContent(state, config, layout.panels, panel) }
    Surface(modifier.fillMaxSize().testTag(ShellTags.layout(layout.placement)), color = MaterialTheme.colorScheme.background) {
        if (layout.placement == StripPlacement.BottomDock) {
            Column(Modifier.fillMaxSize()) {
                TopBar(state, config)
                CanvasArea(state, config, Modifier.weight(1f), canvas)
                if (state.panelTab != null) panelContent()
                strip(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                strip(Modifier.fillMaxHeight())
                Column(Modifier.weight(1f)) {
                    TopBar(state, config)
                    CanvasArea(state, config, Modifier.weight(1f), canvas)
                }
                if (layout.panels == PanelHost.PersistentPane || state.panelTab != null) panelContent()
            }
        }
    }
}

@Composable
private fun TopBar(state: SketchEditorState, config: SketchShellConfig) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(48.dp).testTag(ShellTags.TOP_BAR).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(config.title, Modifier.weight(1f))
        Text(
            if (state.fingerDrawing) "Finger: On" else "Finger: Off",
            Modifier.testTag(ShellTags.FINGER_CHIP).clickable { state.fingerDrawing = !state.fingerDrawing }
                .size(width = 88.dp, height = TouchTargetFloor.floorDp(config.pointer).dp.coerceAtLeast(48.dp)),
        )
        Box {
            Text(
                "⋮",
                Modifier.testTag(ShellTags.OVERFLOW).size(48.dp).clickable { menu = true }
                    .semantics { contentDescription = "More options" },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Constrain (K)") },
                    onClick = { menu = false; config.actions.onConstrain() },
                    modifier = Modifier.testTag(ShellTags.OVERFLOW_CONSTRAIN),
                )
                for (tool in config.model.overflowTools) {
                    DropdownMenuItem(
                        text = { Text("More tools: ${tool.label}  ${tool.shortcut}") },
                        onClick = { menu = false; state.chooseTool(tool.id) },
                        modifier = Modifier.testTag(ShellTags.overflowTool(tool.id)),
                    )
                }
            }
        }
    }
}

/**
 * The canvas with the overlays that must never meet the strip, the chip row or each other: the action chip row at the
 * bottom start and the view cluster at the bottom end, kept clear of the host's own bottom-end button by `endInset`.
 * The strip is outside this box, so it can never cover them.
 */
@Composable
private fun CanvasArea(state: SketchEditorState, config: SketchShellConfig, modifier: Modifier = Modifier, canvas: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth().testTag(ShellTags.CANVAS)) {
        canvas()
        Text(
            hintLine(state),
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
        ViewCluster(state, config, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp + config.endInset, bottom = 8.dp))
    }
}

private fun hintLine(state: SketchEditorState): String {
    val base = HINTS[state.activeTool].orEmpty()
    return if (state.toolLock) "Tool lock on. $base" else base
}

private val HINTS = mapOf(
    ToolId.Select to "Click to select, drag to move.",
    ToolId.Hand to "Drag to move the view.",
    ToolId.Rectangle to "Drag to draw.",
    ToolId.Diamond to "Drag to draw.",
    ToolId.Ellipse to "Drag to draw.",
    ToolId.Arrow to "Drag to draw.",
    ToolId.Line to "Click points to draw.",
    ToolId.Freedraw to "Drag to draw.",
    ToolId.Text to "Click to type.",
    ToolId.Image to "Choose a file, then click to place it.",
    ToolId.Frame to "Drag around elements to frame them.",
    ToolId.Eraser to "Drag across elements to erase.",
    ToolId.Point to "Click to place a point.",
    ToolId.Arc to "Click the start, the end, then a point on the arc.",
)

/** Zoom out, zoom level, zoom in, grid and snap; every target is at least the floor of the pointer class. */
@Composable
private fun ViewCluster(state: SketchEditorState, config: SketchShellConfig, modifier: Modifier = Modifier) {
    val target = 44.dp
    var grid by remember { mutableStateOf(false) }
    var snap by remember { mutableStateOf(false) }
    Row(modifier.testTag(ShellTags.VIEW_CLUSTER), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ClusterButton("sketch.zoomout", "Zoom out", "−", target) { state.viewport = state.viewport.zoomBy(centre, ZOOM_STEP_OUT) }
        ClusterButton("sketch.zoomlevel", "Zoom ${(state.viewport.zoom * PERCENT).toInt()}%", "${(state.viewport.zoom * PERCENT).toInt()}%", target) {
            state.viewport = state.viewport.zoomTo(centre, 1.0)
        }
        ClusterButton("sketch.zoomin", "Zoom in", "+", target) { state.viewport = state.viewport.zoomBy(centre, ZOOM_STEP_IN) }
        ClusterButton("sketch.grid", if (grid) "Grid on" else "Grid off", "#", target) { grid = !grid }
        ClusterButton("sketch.snap", if (snap) "Snap on" else "Snap off", "⊙", target) { snap = !snap }
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
private fun PanelHostContent(state: SketchEditorState, config: SketchShellConfig, host: PanelHost, panel: @Composable (PanelTab) -> Unit) {
    val tabs = PanelTab.entries.filter { it != PanelTab.Layers || config.hasMultipleLayers }
    val open = state.panelTab ?: PanelTab.Style
    val shape = when (host) {
        PanelHost.BottomSheet -> Modifier.fillMaxWidth().height(PANEL_SHEET_HEIGHT)
        PanelHost.SideSheet, PanelHost.PersistentPane -> Modifier.fillMaxHeight().width(PANE_WIDTH)
    }
    Column(shape.testTag(ShellTags.PANEL).background(MaterialTheme.colorScheme.surfaceVariant)) {
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
private val PANE_WIDTH = 320.dp
