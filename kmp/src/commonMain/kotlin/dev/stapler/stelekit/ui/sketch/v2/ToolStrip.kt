// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

/** What the strip shows besides the tools: undo and redo availability, selection size and why tools may be blocked. */
data class ToolStripStatus(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val selectionCount: Int = 0,
    val block: ToolBlock? = null,
)

/** What the strip's buttons do. */
data class ToolStripActions(
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onConstrain: () -> Unit = {},
)

/** Test tags of the strip, one per control, so a test finds a button without reading a label. */
object StripTags {
    const val STRIP = "sketch.strip"
    const val UNDO = "sketch.undo"
    const val REDO = "sketch.redo"
    const val DELETE = "sketch.delete"
    const val CONSTRAIN = "sketch.constrain"
    const val TOOL_LOCK = "sketch.toollock"
    fun tool(id: ToolId) = "sketch.tool.${id.name.lowercase()}"
    fun groupMain(group: StripGroup) = "sketch.group.${group.name.lowercase()}.main"
    fun groupFlyoutTarget(group: StripGroup) = "sketch.group.${group.name.lowercase()}.flyout"
    fun groupItem(group: StripGroup, id: ToolId) = "sketch.group.${group.name.lowercase()}.item.${id.name.lowercase()}"
}

private val GLYPHS = mapOf(
    ToolId.Select to "↖", ToolId.Hand to "✋", ToolId.Rectangle to "▭", ToolId.Diamond to "◇",
    ToolId.Ellipse to "◯", ToolId.Arrow to "↗", ToolId.Line to "╱", ToolId.Freedraw to "✎",
    ToolId.Text to "T", ToolId.Image to "Img", ToolId.Frame to "▢", ToolId.Eraser to "⌫",
    ToolId.Point to "⌖", ToolId.Arc to "◠",
)

/** Glyph size: the default 14 sp read as specks inside a 44 dp touch target on a phone. */
private const val GLYPH_SP = 22

private class StripControls(val focus: Map<String, FocusRequester>, val state: SketchEditorState, val pointer: PointerClass)

/**
 * The tool strip (Story 3.1.3): Undo, Redo and Delete pinned, then the tools (flat, or one split button per group when
 * [stripExtentDp] cannot hold them flat), Constrain and Tool lock. [placement] picks a row (bottom dock) or a column (left
 * rail). Every button is laid out at the chrome table's size in every pointer class; [pointer] only widens a hit area up
 * to the touch floor, so nothing moves under the pointer when a finger first touches the screen.
 */
@Composable
fun ToolStrip(
    model: ToolStripModel,
    state: SketchEditorState,
    placement: StripPlacement,
    stripExtentDp: Int,
    pointer: PointerClass,
    status: ToolStripStatus,
    actions: ToolStripActions,
    modifier: Modifier = Modifier,
) {
    val keys = remember(model) { buildList { add("undo"); add("redo"); add("delete"); addAll(model.tools.map { it.key }); add("constrain"); add("toollock") } + model.groups.flatMap { listOf("group.${it.name}", "group.${it.name}.flyout") } }
    val focus = remember(keys) { keys.associateWith { FocusRequester() } }
    val controls = StripControls(focus, state, pointer)
    LaunchedEffect(placement) {
        state.focusedControl?.let { key -> focus[key]?.let { runCatching { it.requestFocus() } } }
    }
    val vertical = placement == StripPlacement.LeftRail
    val flat = model.isFlat(stripExtentDp)
    StripAxis(vertical, modifier.testTag(StripTags.STRIP)) {
        PinnedTrio(controls, status, actions, vertical)
        Spacer(Modifier.size(if (vertical) 0.dp else 8.dp, if (vertical) 8.dp else 0.dp))
        val scroll = rememberScrollState()
        val scrolling = if (vertical) Modifier.verticalScroll(scroll) else Modifier.horizontalScroll(scroll)
        StripAxis(vertical, scrolling) {
            ToolButtons(model, controls, status, flat, vertical)
            ConstrainButton(model, controls, status, actions)
            ToolLockButton(controls)
        }
    }
}

@Composable
private fun StripAxis(vertical: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // One movable body so the buttons keep their state when the strip turns from a row into a column.
    val body = remember(content) { movableContentOf { content() } }
    if (vertical) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(0.dp), horizontalAlignment = Alignment.CenterHorizontally) { body() }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) { body() }
    }
}

@Composable
private fun PinnedTrio(c: StripControls, status: ToolStripStatus, actions: ToolStripActions, vertical: Boolean) {
    val size = TouchTargetFloor.pinnedDp()
    val none = status.selectionCount == 0
    StripAxis(vertical) {
        StripButton(c, "undo", StripTags.UNDO, "Undo", "↶", size, selected = false, enabled = status.canUndo, onClick = actions.onUndo)
        StripButton(c, "redo", StripTags.REDO, "Redo", "↷", size, selected = false, enabled = status.canRedo, onClick = actions.onRedo)
        Spacer(Modifier.size(if (vertical) 0.dp else TouchTargetFloor.DELETE_GAP_DP.dp, if (vertical) TouchTargetFloor.DELETE_GAP_DP.dp else 0.dp))
        StripButton(c, "delete", StripTags.DELETE, if (none) "Delete, unavailable: nothing selected" else "Delete", "🗑", size, selected = false, enabled = !none, onClick = actions.onDelete)
    }
}

@Composable
private fun ToolButtons(model: ToolStripModel, c: StripControls, status: ToolStripStatus, flat: Boolean, vertical: Boolean) {
    val emitted = HashSet<StripGroup>()
    StripAxis(vertical) {
        for (tool in model.stripTools) {
            val group = tool.group
            if (group == null || flat) {
                ToolButton(model, tool, c, status)
            } else if (emitted.add(group)) {
                SplitGroup(model, group, c, status, vertical)
            }
        }
    }
}

@Composable
private fun ToolButton(model: ToolStripModel, tool: StripTool, c: StripControls, status: ToolStripStatus) {
    StripButton(
        c, tool.key, StripTags.tool(tool.id), model.description(tool, status.block), GLYPHS.getValue(tool.id), TouchTargetFloor.TOUCH_DP,
        selected = c.state.activeTool == tool.id, enabled = true,
        onClick = { if (status.block == null) c.state.chooseTool(tool.id) },
    )
}

/**
 * A split button: the main target activates the group's last-used tool, a separate flyout target opens the group's list.
 * The flyout also opens from the main target by long press (touch), right-click or the stylus barrel button (pointer), and
 * Down or the context-menu key (keyboard), so no tool is reachable only by an undiscoverable gesture (UX-97, UX-109; D-11).
 * Closing the flyout returns focus to the main target.
 */
@Composable
private fun SplitGroup(model: ToolStripModel, group: StripGroup, c: StripControls, status: ToolStripStatus, vertical: Boolean) {
    val last = c.state.lastUsedInGroup[group] ?: group.tools.first()
    val tool = model.tool(last)
    var open by remember { mutableStateOf(false) }
    val mainKey = "group.${group.name}"
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (wasOpen && !open) c.focus[mainKey]?.let { runCatching { it.requestFocus() } }
        wasOpen = open
    }
    val body = @Composable {
        StripButton(
            c, mainKey, StripTags.groupMain(group), model.description(tool, status.block), GLYPHS.getValue(last), TouchTargetFloor.TOUCH_DP,
            selected = c.state.activeTool in group.tools, enabled = true,
            onClick = { if (status.block == null) c.state.chooseTool(last) },
            opensFlyout = { open = true },
        )
        Box {
            StripButton(
                c, "group.${group.name}.flyout", StripTags.groupFlyoutTarget(group), SketchStrings.Strip.flyoutDescription(group.label), "▾", TouchTargetFloor.TOUCH_DP,
                selected = open, enabled = true, onClick = { open = true },
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.testTag(StripTags.groupFlyoutTarget(group) + ".menu")) {
                for (id in group.tools) {
                    val item = model.tool(id)
                    DropdownMenuItem(
                        text = { Text("${item.label}  ${item.shortcut}") },
                        onClick = { open = false; if (status.block == null) c.state.chooseTool(id) },
                        modifier = Modifier.testTag(StripTags.groupItem(group, id)),
                    )
                }
            }
        }
    }
    // Side by side in the dock, stacked in the rail (a split button's targets lie along the strip axis).
    StripAxis(vertical, content = body)
}

@Composable
private fun ConstrainButton(model: ToolStripModel, c: StripControls, status: ToolStripStatus, actions: ToolStripActions) {
    val selected = status.selectionCount > 0
    // Never absent on technical sheets; on a diagram sheet it appears when something is selected (UX-118).
    if (!selected && !model.technicalSheet) return
    val label = if (selected) "Constrain (K)" else "Constrain, unavailable: Select an element to constrain"
    StripButton(c, "constrain", StripTags.CONSTRAIN, label, "⌖↔", TouchTargetFloor.TOUCH_DP, selected = false, enabled = selected, onClick = actions.onConstrain)
}

@Composable
private fun ToolLockButton(c: StripControls) {
    val on = c.state.toolLock
    StripButton(
        c, "toollock", StripTags.TOOL_LOCK, if (on) "Tool lock on" else "Tool lock off", if (on) "🔒" else "🔓",
        TouchTargetFloor.TOUCH_DP, selected = on, enabled = true, onClick = { c.state.toolLock = !c.state.toolLock },
    )
}

/**
 * One strip button. A disabled button stays focusable and announces why (ux.md section 2, error states); its visible
 * size is [sizeDp] in every pointer class and its hit area is never below the pointer class's floor. [opensFlyout] marks
 * the main target of a split group: long press, a secondary press and the Down or context-menu key open the flyout through
 * it, and the hold-for-help of the other buttons gives way to it (their help stays on focus and in the semantics).
 */
@Composable
private fun StripButton(
    c: StripControls, key: String, tag: String, description: String, glyph: String, sizeDp: Int,
    selected: Boolean, enabled: Boolean, onClick: () -> Unit, opensFlyout: (() -> Unit)? = null,
) {
    val hit = maxOf(sizeDp, TouchTargetFloor.floorDp(c.pointer))
    val requester = c.focus.getValue(key)
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val help = HelpEntry(key, description, description)
    val longPress = opensFlyout ?: { c.state.help.show(help) }
    Box(
        Modifier
            .size(hit.dp)
            .testTag(tag)
            .background(background)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) c.state.focusedControl = key }
            .helpOnFocus(c.state.help, help)
            .flyoutKeys(opensFlyout)
            .onSecondaryPress(opensFlyout)
            .semantics {
                contentDescription = description
                role = Role.Button
                this.selected = selected
                if (!enabled) stateDescription = "unavailable"
            }
            .combinedClickable(onLongClickLabel = if (opensFlyout != null) SketchStrings.Strip.FLYOUT_OPEN_LABEL else SketchStrings.Strip.SHOW_HELP_LABEL, onLongClick = longPress) {
                if (enabled) {
                    // The strip is the anchor of walkthrough step 1: using any of its controls ends the tour (ux.md section 22).
                    c.state.walkthrough.anchorActivated(WalkthroughStep.Tools)
                    onClick()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = GLYPH_SP.sp, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Down arrow or the context-menu key on a split group's main target opens its flyout; every other key is left alone. */
private fun Modifier.flyoutKeys(opensFlyout: (() -> Unit)?): Modifier =
    if (opensFlyout == null) this else onKeyEvent { e ->
        val opens = e.type == KeyEventType.KeyDown && (e.key == Key.DirectionDown || e.key == Key.Menu)
        if (opens) opensFlyout()
        opens
    }

/** A right-click or the stylus barrel button (both arrive as a secondary press) on a split group's main target opens its flyout. */
@Composable
private fun Modifier.onSecondaryPress(action: (() -> Unit)?): Modifier {
    if (action == null) return this
    val current by rememberUpdatedState(action)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) current()
            }
        }
    }
}
