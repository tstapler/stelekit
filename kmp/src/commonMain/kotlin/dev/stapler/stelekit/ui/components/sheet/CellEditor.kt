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
