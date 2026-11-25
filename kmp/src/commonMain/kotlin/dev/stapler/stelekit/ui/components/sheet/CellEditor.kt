// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.table.Chord

/** What a key press means inside the editing cell (ADR-009). */
internal sealed interface CellEditorKey {
    data class Enter(val back: Boolean) : CellEditorKey
    data class Tab(val back: Boolean) : CellEditorKey
    data object Cancel : CellEditorKey
    data object LineBreak : CellEditorKey
}

/**
 * Maps a key-down to a grid action, or null to leave it to the text field. While an IME composition is
 * active ([composing]) nothing is consumed, so a candidate is never committed or cancelled by accident.
 */
internal fun resolveCellEditorKey(
    key: Key,
    shift: Boolean,
    alt: Boolean,
    ctrl: Boolean,
    meta: Boolean,
    composing: Boolean,
): CellEditorKey? {
    if (composing || ctrl || meta) return null
    return when (key) {
        Key.Enter, Key.NumPadEnter -> if (alt) CellEditorKey.LineBreak else CellEditorKey.Enter(back = shift)
        Key.Tab -> if (alt) null else CellEditorKey.Tab(back = shift)
        Key.Escape -> CellEditorKey.Cancel
        else -> null
    }
}

/** The chord for a key press in `Navigate`, in the DOM-style names of `TableShortcutMap`; null for keys it does not bind. */
internal fun navigateChord(key: Key, mod: Boolean, shift: Boolean, alt: Boolean): Chord? {
    val name = when (key) {
        Key.Enter, Key.NumPadEnter -> "Enter"
        Key.Tab -> "Tab"
        Key.Escape -> "Escape"
        Key.DirectionUp -> "ArrowUp"
        Key.DirectionDown -> "ArrowDown"
        Key.DirectionLeft -> "ArrowLeft"
        Key.DirectionRight -> "ArrowRight"
        Key.F2 -> "F2"
        Key.A -> "A"
        Key.C -> "C"
        Key.Y -> "Y"
        Key.Z -> "Z"
        else -> return null
    }
    return Chord(name, mod = mod, shift = shift, alt = alt)
}

/** The one live text field of an inline table: only the active cell composes it. */
@Composable
internal fun CellEditor(
    state: InlineTableState,
    textAlign: TextAlign,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val address = state.draft?.address
    LaunchedEffect(address) { focusRequester.requestFocus() }
    // UNVERIFIED on device: Compose exposes no IME_FLAG_NO_EXTRACT_UI / NO_FULLSCREEN knob, so a landscape
    // IME may still go fullscreen; spike 0.4a / Task 8.2a decide whether a platform option is needed.
    BasicTextField(
        value = state.fieldValue,
        onValueChange = state::onFieldChange,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor, textAlign = textAlign),
        cursorBrush = SolidColor(textColor),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { state.handleKey(CellEditorKey.Enter(back = false)) }),
        modifier = modifier
            .widthIn(min = 48.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (state.fieldValue.composition == null && handleCellChord(state, event)) return@onPreviewKeyEvent true
                val resolved = resolveCellEditorKey(
                    key = event.key,
                    shift = event.isShiftPressed,
                    alt = event.isAltPressed,
                    ctrl = event.isCtrlPressed,
                    meta = event.isMetaPressed,
                    composing = state.fieldValue.composition != null,
                ) ?: return@onPreviewKeyEvent false
                state.handleKey(resolved)
            },
    )
}

/** Ctrl or Cmd chords inside the cell: draft-scoped undo and redo (UX-86) and Ctrl+Shift+Enter to maximize. */
private fun handleCellChord(state: InlineTableState, event: KeyEvent): Boolean {
    val mod = event.isCtrlPressed || event.isMetaPressed
    if (!mod) return false
    return when {
        event.key == Key.Z && event.isShiftPressed -> true.also { state.draftRedo() }
        event.key == Key.Z -> true.also { state.draftUndo() }
        event.key == Key.Y -> true.also { state.draftRedo() }
        (event.key == Key.Enter || event.key == Key.NumPadEnter) && event.isShiftPressed ->
            state.hooks.openSheet != null && true.also { state.maximize() }
        else -> false
    }
}
