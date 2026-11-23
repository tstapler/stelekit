// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The name and the reason or explanation of a control, glyph or disabled item: what a hover tooltip shows on desktop. */
data class HelpEntry(val id: String, val name: String, val text: String)

object HelpTags {
    const val POPUP = "sketch.help.popup"
    const val CLOSE = "sketch.help.close"
    fun infoButton(id: String) = "sketch.help.info.$id"
}

/**
 * The touch and keyboard equivalent of a hover tooltip (WCAG 1.4.13, UX-90). A long press on touch, the "i" affordance of a
 * panel or keyboard focus shows the same text as a popup that stays until dismissed (Close, Esc or a new entry) and that the
 * pointer may move onto, so it never disappears under the user. One popup at a time. Pure Compose state.
 */
class HelpPopupState {
    var shown: HelpEntry? by mutableStateOf(null)
        private set

    private var followsFocus = false

    /** The user asked for it (long press, the "i" affordance): it stays until dismissed. */
    fun show(entry: HelpEntry) {
        shown = entry
        followsFocus = false
    }

    /** Keyboard focus reached [entry] (the caller checks the input mode, so a mouse click shows none): the popup follows focus. */
    fun showForFocus(entry: HelpEntry) {
        shown = entry
        followsFocus = true
    }

    /** Focus left [entry]: a popup that only followed focus goes with it, one the user opened stays. */
    fun blurred(entry: HelpEntry) {
        if (shown == entry && followsFocus) dismiss()
    }

    fun dismiss() {
        shown = null
        followsFocus = false
    }
}

/** The semantics text of a control with help: its name, then the same text the popup shows. */
fun HelpEntry.describedBy(): String = if (text.isBlank() || text == name) name else "$name. $text"

/** The popup. Esc and Close dismiss it; it is a polite live region so a screen reader announces it as it appears. */
@Composable
fun HelpPopupHost(state: HelpPopupState, modifier: Modifier = Modifier) {
    val entry = state.shown ?: return
    Surface(
        modifier
            .testTag(HelpTags.POPUP)
            .widthIn(max = POPUP_WIDTH)
            .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = entry.describedBy() }
            .onPreviewKeyEvent { e -> isEscapeDown(e.type, e.key).also { if (it) state.dismiss() } },
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = MaterialTheme.shapes.small,
        shadowElevation = ELEVATION,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(entry.name, style = MaterialTheme.typography.labelLarge)
                if (entry.text.isNotBlank() && entry.text != entry.name) Text(entry.text, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = state::dismiss, modifier = Modifier.defaultMinSize(minWidth = CLOSE_MIN, minHeight = CLOSE_MIN).testTag(HelpTags.CLOSE)) {
                Text("Close")
            }
        }
    }
}

/** Keyboard focus shows [entry]'s help and leaving it hides what focus showed; a mouse or touch focus shows nothing. */
@Composable
fun Modifier.helpOnFocus(state: HelpPopupState, entry: HelpEntry): Modifier {
    val input = LocalInputModeManager.current
    return onFocusChanged { f ->
        if (!f.isFocused) {
            state.blurred(entry)
        } else if (input.inputMode == InputMode.Keyboard) {
            state.showForFocus(entry)
        }
    }
}

/** A mouse hovering over the control shows [entry] and leaving hides it; a finger never hovers, so touch keeps its long press. Never consumes an event. */
fun Modifier.helpOnHover(state: HelpPopupState, entry: HelpEntry): Modifier = pointerInput(entry) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val mouse = event.changes.any { it.type == PointerType.Mouse }
            if (mouse && event.type == PointerEventType.Enter) state.showForFocus(entry)
            if (event.type == PointerEventType.Exit) state.blurred(entry)
        }
    }
}

/** For a control with no click of its own (a glyph, a read-only label): long press and keyboard focus show its help, and its semantics carry the text. */
@Composable
fun Modifier.helpable(state: HelpPopupState, entry: HelpEntry): Modifier =
    helpOnFocus(state, entry)
        .pointerInput(entry) { detectTapGestures(onLongPress = { state.show(entry) }) }
        .semantics {
            contentDescription = entry.describedBy()
            onLongClick(label = "Show help") { state.show(entry); true }
        }

/** The "i" affordance of a panel: a 44 dp button that opens the help of [entry]. */
@Composable
fun HelpInfoButton(state: HelpPopupState, entry: HelpEntry, modifier: Modifier = Modifier) {
    Box(
        modifier.size(INFO_SIZE).testTag(HelpTags.infoButton(entry.id))
            .semantics { role = Role.Button; contentDescription = "Help: ${entry.name}" }
            .clickable { state.show(entry) },
        contentAlignment = Alignment.Center,
    ) { Text("i") }
}

private fun isEscapeDown(type: KeyEventType, key: Key): Boolean = type == KeyEventType.KeyDown && key == Key.Escape

private val POPUP_WIDTH = 280.dp
private val CLOSE_MIN = 44.dp
private val INFO_SIZE = 44.dp
private val ELEVATION = 4.dp
