// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchChromeStrings
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

private class StripControls(val focus: Map<String, FocusRequester>, val state: SketchEditorState, val pointer: PointerClass)

/** Edge fade and selected-pill sizes of the strip, in dp. */
private object StripLook {
    val FADE: Dp = 28.dp
    val CHEVRON: Dp = 5.dp
    val CHEVRON_STROKE: Dp = 2.dp
    val PILL_INSET: Dp = 4.dp
    val PILL_RADIUS: Dp = 14.dp
    val CARET: Dp = 16.dp
    val ICON: Dp = 24.dp
    const val DISABLED_ALPHA = 0.38f
}

/**
 * The tool strip (Story 3.1.3): Undo, Redo and Delete pinned, then the tools (flat, or one split button per group when
 * [stripExtentDp] cannot hold them flat), Constrain and Tool lock. [placement] picks a row (bottom dock) or a column (left
 * rail). Every button is laid out at the chrome table's size in every pointer class; [pointer] only widens a hit area up
 * to the touch floor, so nothing moves under the pointer when a finger first touches the screen.
 *
 * When the tools are grouped in a dock the strip still scrolls, so it never hides the one tool every user needs: Select is
 * pinned beside Undo, Redo and Delete, the scrolling part fades at the edge that has more behind it, and the active tool is
 * scrolled into view.
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
    val pinSelect = !vertical && !flat
    StripAxis(vertical, modifier.testTag(StripTags.STRIP)) {
        PinnedTrio(controls, status, actions, vertical)
        if (pinSelect) ToolButton(model, model.tool(ToolId.Select), controls, status)
        Spacer(Modifier.size(if (vertical) 0.dp else 8.dp, if (vertical) 8.dp else 0.dp))
        val scroll = rememberScrollState()
        // The fade comes first so it is drawn over the visible window, not over the scrolled content behind it.
        val scrolling = Modifier.scrollEdgeFade(scroll, vertical, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant)
            .then(if (vertical) Modifier.verticalScroll(scroll) else Modifier.horizontalScroll(scroll))
        StripAxis(vertical, scrolling) {
            ToolButtons(model, controls, status, flat, vertical, skipSelect = pinSelect)
            ConstrainButton(model, controls, status, actions)
            ToolLockButton(controls)
        }
    }
}

/** Fades the edge of a scrolling strip that still has content behind it, so a strip that scrolls always looks like it does. */
internal fun Modifier.scrollEdgeFade(scroll: ScrollState, vertical: Boolean, color: Color, ink: Color): Modifier = drawWithContent {
    drawContent()
    val fade = StripLook.FADE.toPx().coerceAtMost((if (vertical) size.height else size.width) / 2f)
    val clear = color.copy(alpha = 0f)
    val extent = if (vertical) size.height else size.width
    val across = if (vertical) size.width else size.height
    val reach = StripLook.CHEVRON.toPx()
    // A chevron on the faded edge says which way there is more: it points along the scroll axis, away from the content already seen.
    fun chevron(tip: Float, toward: Float) {
        val back = tip - toward * reach
        val mid = across / 2f
        val path = Path().apply {
            if (vertical) { moveTo(mid - reach, back); lineTo(mid, tip); lineTo(mid + reach, back) }
            else { moveTo(back, mid - reach); lineTo(tip, mid); lineTo(back, mid + reach) }
        }
        drawPath(path, ink, style = Stroke(StripLook.CHEVRON_STROKE.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    if (scroll.value > 0) {
        val brush = if (vertical) Brush.verticalGradient(listOf(color, clear), 0f, fade) else Brush.horizontalGradient(listOf(color, clear), 0f, fade)
        drawRect(brush, Offset.Zero, if (vertical) Size(size.width, fade) else Size(fade, size.height))
        chevron(fade / 3f, -1f)
    }
    if (scroll.canScrollForward) {
        val from = extent - fade
        val brush = if (vertical) Brush.verticalGradient(listOf(clear, color), from, extent) else Brush.horizontalGradient(listOf(clear, color), from, extent)
        drawRect(brush, if (vertical) Offset(0f, from) else Offset(from, 0f), if (vertical) Size(size.width, fade) else Size(fade, size.height))
        chevron(extent - fade / 3f, 1f)
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
        StripButton(c, "undo", StripTags.UNDO, SketchChromeStrings.UNDO, SketchChromeStrings.UNDO_HINT, StripIcon.Undo, size, selected = false, enabled = status.canUndo, onClick = actions.onUndo)
        StripButton(c, "redo", StripTags.REDO, SketchChromeStrings.REDO, SketchChromeStrings.REDO_HINT, StripIcon.Redo, size, selected = false, enabled = status.canRedo, onClick = actions.onRedo)
        Spacer(Modifier.size(if (vertical) 0.dp else TouchTargetFloor.DELETE_GAP_DP.dp, if (vertical) TouchTargetFloor.DELETE_GAP_DP.dp else 0.dp))
        StripButton(c, "delete", StripTags.DELETE, if (none) SketchChromeStrings.DELETE_UNAVAILABLE else SketchChromeStrings.DELETE, SketchChromeStrings.DELETE_HINT, StripIcon.Delete, size, selected = false, enabled = !none, onClick = actions.onDelete)
    }
}

@Composable
private fun ToolButtons(model: ToolStripModel, c: StripControls, status: ToolStripStatus, flat: Boolean, vertical: Boolean, skipSelect: Boolean) {
    val emitted = HashSet<StripGroup>()
    StripAxis(vertical) {
        for (tool in model.stripTools) {
            val group = tool.group
            if (skipSelect && tool.id == ToolId.Select) continue
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
        c, tool.key, StripTags.tool(tool.id), model.description(tool, status.block), SketchChromeStrings.toolHint(tool.id), StripIcon.of(tool.id), TouchTargetFloor.TOUCH_DP,
        selected = c.state.activeTool == tool.id, enabled = true,
        onClick = { if (status.block == null) c.state.chooseTool(tool.id) },
    )
}

/**
 * A split button: the main target activates the group's last-used tool, a separate flyout target opens the group's list.
 * The flyout also opens from the main target by long press (touch), right-click or the stylus barrel button (pointer), and
 * Down or the context-menu key (keyboard), so no tool is reachable only by an undiscoverable gesture (UX-97, UX-109; D-11).
 * Closing the flyout returns focus to the main target. The caret target is drawn as a small filled triangle beside the tool,
 * not as a second tool, and the list names each tool with its shortcut and what it does.
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
            c, mainKey, StripTags.groupMain(group), model.description(tool, status.block), SketchChromeStrings.toolHint(last), StripIcon.of(last), TouchTargetFloor.TOUCH_DP,
            selected = c.state.activeTool in group.tools, enabled = true,
            onClick = { if (status.block == null) c.state.chooseTool(last) },
            opensFlyout = { open = true },
        )
        Box {
            StripButton(
                c, "group.${group.name}.flyout", StripTags.groupFlyoutTarget(group), SketchStrings.Strip.flyoutDescription(group.label), SketchChromeStrings.FLYOUT_HINT,
                StripIcon.More, TouchTargetFloor.TOUCH_DP, selected = false, enabled = true, onClick = { open = true }, iconSize = StripLook.CARET,
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.testTag(StripTags.groupFlyoutTarget(group) + ".menu")) {
                for (id in group.tools) {
                    val item = model.tool(id)
                    DropdownMenuItem(
                        leadingIcon = { StripIconView(StripIcon.of(id), MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Column {
                                Text("${item.label}  ${item.shortcut}")
                                Text(SketchChromeStrings.toolHint(id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
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
    val label = if (selected) SketchChromeStrings.CONSTRAIN else SketchChromeStrings.CONSTRAIN_UNAVAILABLE
    StripButton(c, "constrain", StripTags.CONSTRAIN, label, SketchChromeStrings.CONSTRAIN_HINT, StripIcon.Constrain, TouchTargetFloor.TOUCH_DP, selected = false, enabled = selected, onClick = actions.onConstrain)
}

@Composable
private fun ToolLockButton(c: StripControls) {
    val on = c.state.toolLock
    StripButton(
        c, "toollock", StripTags.TOOL_LOCK, if (on) SketchChromeStrings.TOOL_LOCK_ON else SketchChromeStrings.TOOL_LOCK_OFF, SketchChromeStrings.TOOL_LOCK_HINT, if (on) StripIcon.LockClosed else StripIcon.LockOpen,
        TouchTargetFloor.TOUCH_DP, selected = on, enabled = true, onClick = { c.state.toolLock = !c.state.toolLock },
    )
}

/**
 * One strip button. A disabled button stays focusable and announces why (ux.md section 2, error states); its visible
 * size is [sizeDp] in every pointer class and its hit area is never below the pointer class's floor. [opensFlyout] marks
 * the main target of a split group: long press, a secondary press and the Down or context-menu key open the flyout through
 * it, and the hold-for-help of the other buttons gives way to it (their help stays on focus, on hover and in the semantics).
 * The help popup shows the name, then [hint]; the selected state is a rounded pill, the disabled state a dimmed icon.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StripButton(
    c: StripControls, key: String, tag: String, description: String, hint: String, icon: StripIcon, sizeDp: Int,
    selected: Boolean, enabled: Boolean, onClick: () -> Unit, opensFlyout: (() -> Unit)? = null, iconSize: Dp = StripLook.ICON,
) {
    val hit = maxOf(sizeDp, TouchTargetFloor.floorDp(c.pointer))
    val requester = c.focus.getValue(key)
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) runCatching { bring.bringIntoView() } }
    val scheme = MaterialTheme.colorScheme
    // A control that cannot act says why (its description) instead of what it would do, so the popup never promises an action it will not take.
    val help = HelpEntry(key, description, if (enabled) hint else description)
    val longPress = opensFlyout ?: { c.state.help.show(help) }
    Box(
        Modifier
            .size(hit.dp)
            .testTag(tag)
            .bringIntoViewRequester(bring)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) c.state.focusedControl = key }
            .helpOnFocus(c.state.help, help)
            .helpOnHover(c.state.help, help)
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
        Box(
            Modifier.fillMaxSize().padding(StripLook.PILL_INSET).clip(RoundedCornerShape(StripLook.PILL_RADIUS))
                .background(if (selected) scheme.primaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            val ink = if (selected) scheme.onPrimaryContainer else scheme.onSurface
            StripIconView(icon, if (enabled) ink else scheme.onSurface.copy(alpha = StripLook.DISABLED_ALPHA), size = iconSize)
        }
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
