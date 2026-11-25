// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.table.GridCommand
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.ui.components.TableMessages

/**
 * The sheet's strip under the grid (ux.md S4, S10): Undo and Redo lead, then the range actions that make every drag and
 * shortcut reachable by tap: Copy, Cut, Clear, Paste, Select row, Select column, Select all, Extend..., and the
 * column and row menus. In `Edit` only the field-scoped Undo and Redo show (the edit bar owns the rest). The Extend
 * picker is the same five choices as the inline strip's ([ExtendChoice]).
 */
@Composable
internal fun SheetContextStrip(state: SheetState, session: TableSheetSession, commands: SheetCommands) {
    var pickerOpen by remember(state) { mutableStateOf(false) }
    DisposableEffect(state, pickerOpen) {
        if (!pickerOpen) return@DisposableEffect onDispose { }
        val release = state.registerMenu { pickerOpen = false }
        onDispose(release)
    }
    val labels = rememberUndoRedoLabels(session)
    val editing = state.mode == GridMode.Edit
    Column(Modifier.fillMaxWidth().semantics { isTraversalGroup = true }.testTag("sheetContextStrip")) {
        if (pickerOpen && !editing) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("sheetExtendPicker"), verticalAlignment = Alignment.CenterVertically) {
                ExtendChoice.entries.forEach { choice ->
                    StripButton(choice.label, "extend:${choice.name}") { state.extendRange(choice); pickerOpen = false }
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            UndoRedoButton(labels.undo, labels.undoDimmed, labels.undoNote, session::undoControl, Modifier.testTag("strip:Undo"))
            UndoRedoButton(labels.redo, labels.redoDimmed, labels.redoNote, session::redoControl, Modifier.testTag("strip:Redo"))
            if (!editing) {
                val writable = !state.readOnly
                StripButton(TableMessages.COPY, "strip:Copy") { commands.clipboard.copy() }
                StripButton(TableMessages.CUT, "strip:Cut", writable) { commands.clipboard.cut() }
                StripButton(TableMessages.CLEAR, "strip:Clear", writable) { commands.clearSelection() }
                StripButton(TableMessages.PASTE, "strip:Paste", writable) { commands.clipboard.paste() }
                StripButton(TableMessages.SELECT_ROW, "strip:SelectRow") { state.perform(GridCommand.SelectRow) }
                StripButton(TableMessages.SELECT_COLUMN, "strip:SelectColumn") { state.perform(GridCommand.SelectColumn) }
                StripButton(TableMessages.SELECT_ALL, "strip:SelectAll") { state.perform(GridCommand.SelectAll) }
                StripButton(TableMessages.EXTEND, "strip:Extend") { pickerOpen = !pickerOpen }
                StripButton(TableMessages.COLUMN_MENU, "strip:ColumnMenu") { state.openMenu(SheetMenuTarget.Column(state.selection.anchor.col)) }
                StripButton(TableMessages.ROW_MENU, "strip:RowMenu") { state.openMenu(SheetMenuTarget.Row(state.selection.anchor.row)) }
            }
        }
    }
}

@Composable
private fun StripButton(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).testTag(tag)) {
        Text(text, maxLines = 1)
    }
}
