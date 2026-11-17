// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.scene.BindingEnd
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.tools.ToolCommand
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

object NonDragTags {
    const val PANEL = "sketch.nd.panel"
    const val PREVIEW = "sketch.nd.preview"
    const val APPLY = "sketch.nd.apply"
    const val CANCEL = "sketch.nd.cancel"
    const val NOTICE = "sketch.nd.notice"
    const val BIND = "sketch.nd.action.bind"
    const val MARQUEE = "sketch.nd.action.marquee"
    const val PLACE_CORNER = "sketch.nd.corner"
    const val SELECT = "sketch.nd.select"
    fun end(end: BindingEnd) = "sketch.nd.end.${end.name.lowercase()}"
    fun candidate(id: ElementId) = "sketch.nd.candidate.${id.value}"
    fun action(action: NonDragAction) = "sketch.nd.action.${action.name.lowercase()}"
    fun step(step: NudgeStep) = "sketch.nd.step.${step.name.lowercase()}"
    fun nudge(name: String) = "sketch.nd.nudge.$name"
}

/** What the panel reports: the ONE command Apply produced, the notice to show (with how many locked elements were skipped), and leaving the mode. */
class NonDragCallbacks(
    val onCommand: (ToolCommand) -> Unit,
    val onNotice: (NonDragNotice, Int) -> Unit,
    val onExit: () -> Unit,
    /** The marquee's Select: the ids to select, in paint order (never empty). */
    val onSelect: (List<ElementId>) -> Unit = {},
)

/** The panel's state: the chosen step, the active mode (transform, bind end or marquee) and why an action was refused. */
class NonDragPanelState {
    var step by mutableStateOf(NudgeStep.Ten)
        private set
    var mode by mutableStateOf<NonDragTransformMode?>(null)
        private set
    var bind by mutableStateOf<NonDragBindMode?>(null)
        private set
    var marquee by mutableStateOf<NonDragMarqueeMode?>(null)
        private set
    var refusal by mutableStateOf<NonDragNotice?>(null)
        private set

    private fun reset() {
        mode = null
        bind = null
        marquee = null
        refusal = null
    }

    fun begin(action: NonDragAction, scene: SketchSceneV2, selection: List<ElementId>) {
        reset()
        when (val started = NonDragTransformMode.begin(action, scene, selection, step)) {
            is NonDragStart.Started -> mode = started.mode
            is NonDragStart.Refused -> refusal = started.notice
        }
    }

    fun beginBind(scene: SketchSceneV2, selection: List<ElementId>) {
        reset()
        when (val started = NonDragBindMode.begin(scene, selection)) {
            is BindStart.Started -> bind = started.mode
            is BindStart.Refused -> refusal = started.notice
        }
    }

    fun beginMarquee(scene: SketchSceneV2, x: Double, y: Double) {
        reset()
        marquee = NonDragMarqueeMode.begin(scene, x, y, step)
    }

    fun chooseEnd(end: BindingEnd) {
        bind = bind?.withEnd(end)
    }

    fun pick(id: ElementId) {
        bind = bind?.pick(id)
    }

    fun placeCorner() {
        marquee = marquee?.placeCorner()
    }

    fun chooseStep(next: NudgeStep) {
        step = next
        mode = mode?.withStep(next)
        marquee = marquee?.withStep(next)
    }

    fun nudge(dx: Int, dy: Int) {
        mode = mode?.nudge(dx, dy)
        marquee = marquee?.nudge(dx, dy)
    }

    fun nudgeFar(dx: Int, dy: Int) {
        mode = mode?.nudgeFar(dx, dy)
        marquee = marquee?.nudgeFar(dx, dy)
    }

    /** Runs the pending change as one command (or selects the marquee region) and returns to the action choice. */
    fun apply(cb: NonDragCallbacks) {
        val region = marquee
        if (region != null) {
            if (region.region == null) marquee = region.placeCorner() else selectRegion(region, cb)
            return
        }
        bind?.let { b ->
            val command = b.apply() ?: return
            bind = null
            cb.onCommand(command)
            return
        }
        val done = mode?.apply()
        mode = null
        if (done == null) return
        done.command?.let(cb.onCommand)
        val notice = done.notice ?: NonDragNotice.Locked.takeIf { done.skippedLocked > 0 }
        notice?.let { cb.onNotice(it, done.skippedLocked) }
    }

    /** The marquee's Select: selects what the region holds and leaves the marquee; an empty region selects nothing and stays. */
    fun select(cb: NonDragCallbacks) {
        marquee?.let { selectRegion(it, cb) }
    }

    private fun selectRegion(region: NonDragMarqueeMode, cb: NonDragCallbacks) {
        val ids = region.contained()
        if (ids.isEmpty()) return
        marquee = null
        cb.onSelect(ids)
    }

    /** Cancel and Esc: drop a pending change and stay, or with none pending leave the mode. */
    fun cancel(cb: NonDragCallbacks) {
        val m = mode
        val b = bind
        val q = marquee
        when {
            m != null -> mode = m.afterEscape()
            b != null -> bind = b.afterEscape()
            q != null -> marquee = q.afterEscape()
            else -> {
                refusal = null
                cb.onExit()
            }
        }
    }

    /** The line under the pad: why an action was refused, the clamp notice, or the preview. */
    fun statusText(): String = refusal?.text
        ?: mode?.let { it.pendingNotice()?.text ?: it.previewText() }
        ?: bind?.previewText()
        ?: marquee?.summaryText()
        .orEmpty()
}

/**
 * The non-drag panel of Crosshair mode (ux.md section 10): action buttons (Move element, Resize, Rotate, Bind end, Marquee),
 * a step choice, a four-way nudge pad at the touch floor, the preview line and [Cancel] and [Apply]. Every drag has this
 * single-pointer alternative (UX-40, WCAG 2.5.7). Arrow keys nudge (Shift = 10 units), Enter applies and Esc cancels.
 * [cursorX] and [cursorY] are the crosshair position where a marquee starts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NonDragTransformPanel(
    scene: SketchSceneV2,
    selection: List<ElementId>,
    callbacks: NonDragCallbacks,
    modifier: Modifier = Modifier,
    cursorX: Double = 0.0,
    cursorY: Double = 0.0,
) {
    val state = remember { NonDragPanelState() }
    Surface(
        modifier.testTag(NonDragTags.PANEL).onPreviewKeyEvent { e -> handleKey(e.type, e.key, e.isShiftPressed, state, callbacks) }.focusable(),
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(PANEL_PADDING), verticalArrangement = Arrangement.spacedBy(PANEL_GAP)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(PANEL_GAP)) {
                for (action in NonDragAction.entries) {
                    Chip(NonDragTags.action(action), action.label, selected = state.mode?.action == action) { state.begin(action, scene, selection) }
                }
                Chip(NonDragTags.BIND, SketchStrings.NonDrag.BIND_END, selected = state.bind != null) { state.beginBind(scene, selection) }
                Chip(NonDragTags.MARQUEE, SketchStrings.NonDrag.MARQUEE, selected = state.marquee != null) { state.beginMarquee(scene, cursorX, cursorY) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PANEL_GAP), verticalAlignment = Alignment.CenterVertically) {
                Text(SketchStrings.NonDrag.STEP)
                for (s in NudgeStep.entries) Chip(NonDragTags.step(s), s.label, selected = state.step == s) { state.chooseStep(s) }
            }
            state.bind?.let { BindList(it, state) }
            state.marquee?.let { MarqueeButtons(state, callbacks) }
            if (state.bind == null) NudgePad(state::nudge)
            Text(
                state.statusText(),
                Modifier.testTag(if (state.refusal != null) NonDragTags.NOTICE else NonDragTags.PREVIEW).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(PANEL_GAP)) {
                Chip(NonDragTags.CANCEL, SketchStrings.NonDrag.CANCEL, selected = false) { state.cancel(callbacks) }
                Chip(NonDragTags.APPLY, SketchStrings.NonDrag.APPLY, selected = false) { state.apply(callbacks) }
            }
        }
    }
}

@Composable
private fun MarqueeButtons(state: NonDragPanelState, callbacks: NonDragCallbacks) {
    Row(horizontalArrangement = Arrangement.spacedBy(PANEL_GAP)) {
        Chip(NonDragTags.PLACE_CORNER, SketchStrings.NonDrag.PLACE_CORNER, selected = false) { state.placeCorner() }
        Chip(NonDragTags.SELECT, SketchStrings.NonDrag.SELECT, selected = false) { state.select(callbacks) }
    }
}

/** The end choice and the candidate shapes of Bind end, nearest first; the list scrolls inside its own height. */
@Composable
private fun BindList(mode: NonDragBindMode, state: NonDragPanelState) {
    Column(verticalArrangement = Arrangement.spacedBy(PANEL_GAP)) {
        Row(horizontalArrangement = Arrangement.spacedBy(PANEL_GAP), verticalAlignment = Alignment.CenterVertically) {
            Chip(NonDragTags.end(BindingEnd.Start), SketchStrings.NonDrag.END_START, selected = mode.end == BindingEnd.Start) { state.chooseEnd(BindingEnd.Start) }
            Chip(NonDragTags.end(BindingEnd.End), SketchStrings.NonDrag.END_END, selected = mode.end == BindingEnd.End) { state.chooseEnd(BindingEnd.End) }
        }
        Column(
            Modifier.heightIn(max = LIST_MAX_HEIGHT).verticalScroll(rememberScrollState()).semantics { contentDescription = SketchStrings.NonDrag.TARGETS },
        ) {
            for (c in mode.candidates) {
                Box(
                    Modifier.size(width = LIST_ROW_WIDTH, height = PAD_CELL).testTag(NonDragTags.candidate(c.id))
                        .semantics { role = Role.Button; selected = mode.picked == c.id }
                        .clickable { state.pick(c.id) },
                    contentAlignment = Alignment.CenterStart,
                ) { Text(c.label, color = if (mode.picked == c.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
}

/** Arrow keys nudge (Shift = 10 units), Enter applies, Esc cancels; returns whether the key was used. */
private fun handleKey(type: KeyEventType, key: Key, shift: Boolean, state: NonDragPanelState, cb: NonDragCallbacks): Boolean {
    if (type != KeyEventType.KeyDown) return false
    if (key == Key.Enter) state.apply(cb) else if (key == Key.Escape) state.cancel(cb) else return nudgeKey(key, shift, state)
    return true
}

private fun nudgeKey(key: Key, shift: Boolean, state: NonDragPanelState): Boolean {
    val (dx, dy) = arrowOf(key) ?: return false
    if (shift) state.nudgeFar(dx, dy) else state.nudge(dx, dy)
    return true
}

private fun arrowOf(key: Key): Pair<Int, Int>? = when (key) {
    Key.DirectionLeft -> -1 to 0
    Key.DirectionRight -> 1 to 0
    Key.DirectionUp -> 0 to -1
    Key.DirectionDown -> 0 to 1
    else -> null
}

@Composable
private fun NudgePad(onNudge: (Int, Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PadButton("up", SketchStrings.NonDrag.NUDGE_UP, "↑") { onNudge(0, -1) }
        Row {
            PadButton("left", SketchStrings.NonDrag.NUDGE_LEFT, "←") { onNudge(-1, 0) }
            Box(Modifier.size(PAD_CELL))
            PadButton("right", SketchStrings.NonDrag.NUDGE_RIGHT, "→") { onNudge(1, 0) }
        }
        PadButton("down", SketchStrings.NonDrag.NUDGE_DOWN, "↓") { onNudge(0, 1) }
    }
}

@Composable
private fun PadButton(name: String, description: String, glyph: String, onClick: () -> Unit) {
    Box(
        Modifier.size(PAD_CELL).testTag(NonDragTags.nudge(name))
            .semantics { role = Role.Button; contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph) }
}

@Composable
private fun Chip(tag: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(width = CHIP_WIDTH, height = PAD_CELL).testTag(tag)
            .semantics { role = Role.Button; this.selected = selected }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
}

private val PAD_CELL = 44.dp
private val PANEL_PADDING = 12.dp
private val PANEL_GAP = 8.dp
private val CHIP_WIDTH = 96.dp
private val LIST_ROW_WIDTH = 240.dp
private val LIST_MAX_HEIGHT = 176.dp
