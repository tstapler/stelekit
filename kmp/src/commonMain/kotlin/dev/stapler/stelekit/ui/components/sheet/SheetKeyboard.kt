// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.ui.Modifier
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
import androidx.compose.ui.input.key.utf16CodePoint
import dev.stapler.stelekit.table.Chord
import dev.stapler.stelekit.table.GridCommand
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.TableShortcutMap

/** What the grid does with one key press; the Compose handler is a thin wrapper over [decideSheetKey]. */
internal sealed interface SheetKeyDecision {
    /** An IME composition owns the key: the grid consumes nothing. */
    data object PassToIme : SheetKeyDecision

    data object NotHandled : SheetKeyDecision
    data class Run(val command: GridCommand) : SheetKeyDecision

    /** `Navigate` and a printable key: the character replaces the cell and starts an edit. */
    data class TypeChar(val text: String) : SheetKeyDecision
}

/**
 * [printable] is the character the press types (null for none or for a chord with a modifier). A composing field
 * never loses Enter, Tab, arrows or Esc to the grid (S7, UX-31).
 */
internal fun decideSheetKey(chord: Chord, mode: GridMode, composing: Boolean, printable: String? = null): SheetKeyDecision {
    if (composing) return SheetKeyDecision.PassToIme
    TableShortcutMap.lookup(chord, mode)?.let { return SheetKeyDecision.Run(it) }
    return if (printable != null && typesIntoCell(chord, mode)) SheetKeyDecision.TypeChar(printable) else SheetKeyDecision.NotHandled
}

private fun typesIntoCell(chord: Chord, mode: GridMode): Boolean = mode == GridMode.Navigate && !chord.mod && !chord.alt

/** DOM-style key name of `TableShortcutMap` for a Compose key, or null for a key the map never binds. */
internal fun chordKeyName(key: Key): String? = when (key) {
    Key.Enter, Key.NumPadEnter -> "Enter"
    Key.Tab -> "Tab"
    Key.Escape -> "Escape"
    Key.DirectionUp -> "ArrowUp"
    Key.DirectionDown -> "ArrowDown"
    Key.DirectionLeft -> "ArrowLeft"
    Key.DirectionRight -> "ArrowRight"
    Key.MoveHome -> "Home"
    Key.MoveEnd -> "End"
    Key.PageUp -> "PageUp"
    Key.PageDown -> "PageDown"
    Key.Spacebar -> "Space"
    Key.Delete -> "Delete"
    Key.Backspace -> "Backspace"
    Key.F2 -> "F2"
    Key.F10 -> "F10"
    Key.Menu -> "ContextMenu"
    Key.Equals, Key.Plus, Key.NumPadAdd -> "="
    Key.Minus, Key.NumPadSubtract -> "-"
    Key.A -> "A"
    Key.C -> "C"
    Key.V -> "V"
    Key.X -> "X"
    Key.Y -> "Y"
    Key.Z -> "Z"
    else -> null
}

internal fun KeyEvent.toChord(): Chord? {
    val name = chordKeyName(key) ?: return null
    return Chord(name, mod = isCtrlPressed || isMetaPressed, shift = isShiftPressed, alt = isAltPressed)
}

/** The typed character of a key-down, or null for a control key or a press with Ctrl, Cmd or Alt held. */
internal fun KeyEvent.typedText(): String? {
    if (isCtrlPressed || isMetaPressed || isAltPressed) return null
    val cp = utf16CodePoint
    if (cp < ' '.code || cp == 0x7F) return null
    return cp.toChar().toString()
}

/** Decides one Compose key event for [mode]; `KeyUp` and unmapped keys come back `NotHandled`. */
internal fun decideSheetKey(event: KeyEvent, mode: GridMode, composing: Boolean): SheetKeyDecision {
    if (event.type != KeyEventType.KeyDown) return SheetKeyDecision.NotHandled
    val chord = event.toChord()
    if (chord == null) {
        val typed = if (composing) null else event.typedText()
        return if (mode == GridMode.Navigate && typed != null) SheetKeyDecision.TypeChar(typed)
        else if (composing) SheetKeyDecision.PassToIme else SheetKeyDecision.NotHandled
    }
    return decideSheetKey(chord, mode, composing, event.typedText())
}

/**
 * Keys for the grid in `Navigate` (the edit bar's field owns `Edit`). Esc is left unconsumed so it climbs the one ladder
 * through the host's bubbling handler; every other bound chord, and a printable key, is consumed (ADR-009).
 */
internal fun Modifier.sheetGridKeys(state: SheetState): Modifier = onPreviewKeyEvent { event ->
    if (state.mode != GridMode.Navigate) return@onPreviewKeyEvent false
    when (val decision = decideSheetKey(event, GridMode.Navigate, composing = false)) {
        is SheetKeyDecision.Run -> decision.command != GridCommand.Cancel && state.onKey(decision)
        is SheetKeyDecision.TypeChar -> state.onKey(decision)
        else -> false
    }
}
