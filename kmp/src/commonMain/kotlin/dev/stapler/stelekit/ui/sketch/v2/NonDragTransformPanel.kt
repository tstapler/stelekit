// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.tools.ToolCommand

object NonDragTags {
    const val PANEL = "sketch.nd.panel"
    const val PREVIEW = "sketch.nd.preview"
    const val APPLY = "sketch.nd.apply"
    const val CANCEL = "sketch.nd.cancel"
    const val NOTICE = "sketch.nd.notice"
    fun action(action: NonDragAction) = "sketch.nd.action.${action.name.lowercase()}"
    fun step(step: NudgeStep) = "sketch.nd.step.${step.name.lowercase()}"
    fun nudge(name: String) = "sketch.nd.nudge.$name"
}

/** What the panel reports: the ONE command Apply produced, the notice to show (with how many locked elements were skipped), and leaving the mode. */
class NonDragCallbacks(
    val onCommand: (ToolCommand) -> Unit,
    val onNotice: (NonDragNotice, Int) -> Unit,
    val onExit: () -> Unit,
)

/** The panel's state: the chosen step and action, the pending nudge in [mode], and why an action was refused. */
class NonDragPanelState {
    var step by mutableStateOf(NudgeStep.Ten)
        private set
    var mode by mutableStateOf<NonDragTransformMode?>(null)
        private set
    var refusal by mutableStateOf<NonDragNotice?>(null)
        private set

    fun begin(action: NonDragAction, scene: SketchSceneV2, selection: List<ElementId>) {
        when (val started = NonDragTransformMode.begin(action, scene, selection, step)) {
            is NonDragStart.Started -> {
                mode = started.mode
                refusal = null
            }
            is NonDragStart.Refused -> {
                mode = null
                refusal = started.notice
            }
        }
    }

    fun chooseStep(next: NudgeStep) {
        step = next
        mode = mode?.withStep(next)
    }

    fun nudge(dx: Int, dy: Int) {
        mode = mode?.nudge(dx, dy)
    }

    fun nudgeFar(dx: Int, dy: Int) {
        mode = mode?.nudgeFar(dx, dy)
    }

    /** Runs the pending change as one command and returns to the action choice. */
    fun apply(cb: NonDragCallbacks) {
        val done = mode?.apply()
        mode = null
        if (done == null) return
        done.command?.let(cb.onCommand)
        val notice = done.notice ?: NonDragNotice.Locked.takeIf { done.skippedLocked > 0 }
        notice?.let { cb.onNotice(it, done.skippedLocked) }
    }

    /** Cancel and Esc: drop a pending nudge and stay; with none pending, leave the mode. */
    fun cancel(cb: NonDragCallbacks) {
        val current = mode
        if (current == null) cb.onExit() else mode = current.afterEscape()
    }

    /** The line under the pad: why an action was refused, the clamp notice, or the preview. */
    fun statusText(): String = refusal?.text ?: mode?.let { it.pendingNotice()?.text ?: it.previewText() }.orEmpty()
}

/**
 * The non-drag transform panel of Crosshair mode (ux.md section 10): action buttons, a step choice, a four-way nudge pad at
 * the touch floor, the preview line and [Cancel] and [Apply]. Every drag has this single-pointer alternative (UX-40,
 * WCAG 2.5.7). Arrow keys nudge (Shift = 10 units), Enter applies and Esc cancels.
 */
@Composable
fun NonDragTransformPanel(
    scene: SketchSceneV2,
    selection: List<ElementId>,
    callbacks: NonDragCallbacks,
    modifier: Modifier = Modifier,
) {
    val state = remember { NonDragPanelState() }
    Surface(
        modifier.testTag(NonDragTags.PANEL).onPreviewKeyEvent { e -> handleKey(e.type, e.key, e.isShiftPressed, state, callbacks) }.focusable(),
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(PANEL_PADDING), verticalArrangement = Arrangement.spacedBy(PANEL_GAP)) {
            Row(horizontalArrangement = Arrangement.spacedBy(PANEL_GAP)) {
                for (action in NonDragAction.entries) {
                    Chip(NonDragTags.action(action), action.label, selected = state.mode?.action == action) { state.begin(action, scene, selection) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PANEL_GAP), verticalAlignment = Alignment.CenterVertically) {
                Text("Step")
                for (s in NudgeStep.entries) Chip(NonDragTags.step(s), s.label, selected = state.step == s) { state.chooseStep(s) }
            }
            NudgePad(state::nudge)
            Text(
                state.statusText(),
                Modifier.testTag(if (state.refusal != null) NonDragTags.NOTICE else NonDragTags.PREVIEW).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(PANEL_GAP)) {
                Chip(NonDragTags.CANCEL, "Cancel", selected = false) { state.cancel(callbacks) }
                Chip(NonDragTags.APPLY, "Apply", selected = false) { state.apply(callbacks) }
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
        PadButton("up", "Nudge up", "↑") { onNudge(0, -1) }
        Row {
            PadButton("left", "Nudge left", "←") { onNudge(-1, 0) }
            Box(Modifier.size(PAD_CELL))
            PadButton("right", "Nudge right", "→") { onNudge(1, 0) }
        }
        PadButton("down", "Nudge down", "↓") { onNudge(0, 1) }
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
