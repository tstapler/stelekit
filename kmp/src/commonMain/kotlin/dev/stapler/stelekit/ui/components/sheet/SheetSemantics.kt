// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.GridCommand
import dev.stapler.stelekit.table.GridDirection
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.GridSelection
import dev.stapler.stelekit.ui.components.TableMessages

/**
 * The sheet's accessibility semantics (Story 6.1a, ux.md 3.6): a `grid` with the table's full counts, per-cell
 * position, heading header cells, formula and error state, and TalkBack custom actions that stand in for every drag,
 * shortcut and long-press. Each action selects its target the way a tap would and then calls the same command a key
 * or menu item calls, so a screen-reader user and a keyboard user reach identical code.
 */
internal object SheetSemantics {
    /** Full counts, never the lazy window: [SheetState.rowCount] already counts the header as row 1. */
    fun gridInfo(state: SheetState) = CollectionInfo(rowCount = state.rowCount, columnCount = state.columnCount)

    fun formulaState(display: CellDisplay): String? = when (display) {
        is CellDisplay.Formula -> TableMessages.formulaState(display.formula, display.text)
        is CellDisplay.Failed -> TableMessages.formulaState(display.formula, null)
        is CellDisplay.InvalidFormula, is CellDisplay.Plain -> null
    }

    /** The labels of the header actions, in announcement order; the structural ones drop out at a table edge. */
    fun headerActionLabels(state: SheetState, col: Int): List<String> = buildList {
        if (col > 1) add(TableMessages.MOVE_COLUMN_LEFT)
        if (col < state.columnCount) add(TableMessages.MOVE_COLUMN_RIGHT)
        add(TableMessages.RESIZE_COLUMN)
        add(TableMessages.SORT_AZ)
        add(TableMessages.SORT_ZA)
        add(TableMessages.INSERT_COLUMN_RIGHT)
    }

    fun headerActions(state: SheetState, col: Int): List<CustomAccessibilityAction> {
        val target = SheetMenuTarget.Column(col)
        val byMenu = mapOf(
            TableMessages.MOVE_COLUMN_LEFT to "moveLeft",
            TableMessages.MOVE_COLUMN_RIGHT to "moveRight",
            TableMessages.SORT_AZ to "sortAsc",
            TableMessages.SORT_ZA to "sortDesc",
            TableMessages.INSERT_COLUMN_RIGHT to "insertRight",
        )
        return headerActionLabels(state, col).map { label ->
            CustomAccessibilityAction(label) {
                if (label == TableMessages.RESIZE_COLUMN) {
                    state.selectColumn(col, openMenu = false)
                    state.openMenu(target)
                    true
                } else {
                    // A sort from a header acts on the whole column's rows, not on whatever range was selected before.
                    state.selectColumn(col, openMenu = false)
                    runMenuItem(state, target, byMenu.getValue(label))
                }
            }
        }
    }

    /**
     * Edit cell, extend, select, copy and paste, row moves and inserts. The cell being edited offers only the two
     * edit-bar commands a keyboard reaches by Enter and Alt+Enter, so a screen reader never needs the bar's overflow.
     */
    fun cellActionLabels(state: SheetState, address: CellAddress): List<String> {
        if (state.mode == GridMode.Edit) {
            return if (address == state.editingAddress) listOf(TableMessages.NEXT_COLUMN, TableMessages.LINE_BREAK) else emptyList()
        }
        val writable = !state.readOnly
        val body = address.row >= 2
        return buildList {
            if (writable) add(TableMessages.EDIT_CELL)
            ExtendChoice.entries.forEach { add(TableMessages.extendLabel(it.label.lowercase())) }
            add(TableMessages.SELECT_ROW)
            add(TableMessages.SELECT_COLUMN)
            add(TableMessages.SELECT_ALL)
            add(TableMessages.COPY)
            if (writable) {
                add(TableMessages.PASTE)
                if (body && address.row > 2) add(TableMessages.MOVE_ROW_UP)
                if (body && address.row < state.rowCount) add(TableMessages.MOVE_ROW_DOWN)
                add(TableMessages.INSERT_ROW_BELOW)
                add(TableMessages.INSERT_COLUMN_RIGHT)
            }
        }
    }

    fun cellActions(state: SheetState, address: CellAddress): List<CustomAccessibilityAction> =
        cellActionLabels(state, address).map { label -> CustomAccessibilityAction(label) { runCellAction(state, address, label) } }

    fun runCellAction(state: SheetState, address: CellAddress, label: String): Boolean {
        val menuIds = mapOf(TableMessages.INSERT_ROW_BELOW to "insertBelow", TableMessages.INSERT_COLUMN_RIGHT to "insertRight")
        return when (label) {
            TableMessages.EDIT_CELL -> state.selectCell(address).also { if (it) state.startEdit(address) }
            TableMessages.NEXT_COLUMN -> true.also { state.nextColumn() }
            TableMessages.LINE_BREAK -> true.also { state.insertLineBreak() }
            TableMessages.SELECT_ROW -> true.also { state.selectRow(address.row, openMenu = false) }
            TableMessages.SELECT_COLUMN -> true.also { state.selectColumn(address.col, openMenu = false) }
            TableMessages.SELECT_ALL -> state.selectCell(address) && state.perform(GridCommand.SelectAll)
            TableMessages.COPY -> state.selectCellKeepingRange(address) && state.perform(GridCommand.Copy)
            TableMessages.PASTE -> state.selectCellKeepingRange(address) && state.perform(GridCommand.Paste)
            TableMessages.MOVE_ROW_UP -> state.selectCell(address) && state.perform(GridCommand.MoveSelection(GridDirection.Up))
            TableMessages.MOVE_ROW_DOWN -> state.selectCell(address) && state.perform(GridCommand.MoveSelection(GridDirection.Down))
            TableMessages.INSERT_ROW_BELOW, TableMessages.INSERT_COLUMN_RIGHT ->
                state.selectCell(address) && runMenuItem(state, SheetMenuTarget.Cell(address), menuIds.getValue(label))
            else -> {
                val choice = ExtendChoice.entries.firstOrNull { TableMessages.extendLabel(it.label.lowercase()) == label } ?: return false
                state.selectCellKeepingRange(address)
                state.extendRange(choice)
                true
            }
        }
    }

    /** Runs the menu entry [id] for [target] when it exists and is enabled; false otherwise (the reason is announced). */
    private fun runMenuItem(state: SheetState, target: SheetMenuTarget, id: String): Boolean {
        val item = state.sink.menuFor(target)?.items?.firstOrNull { it.id == id } ?: return false
        if (!item.enabled) {
            item.reason?.let(state::announce)
            return false
        }
        item.action()
        return true
    }
}

/** A semantic action targets [address]: it becomes the selection unless it is already inside it (a range stays a range). */
private fun SheetState.selectCellKeepingRange(address: CellAddress): Boolean =
    if (address in selection) true else selectCell(address)

private fun SheetState.selectCell(address: CellAddress): Boolean = selectRange(GridSelection.single(address, bounds))

/** Position, heading, state and actions of one cell (merged into a single TalkBack stop unless it is being edited). */
internal fun SemanticsPropertyReceiver.sheetCellSemantics(state: SheetState, address: CellAddress, display: CellDisplay) {
    collectionItemInfo = CollectionItemInfo(rowIndex = address.row - 1, rowSpan = 1, columnIndex = address.col - 1, columnSpan = 1)
    markSelected(address in state.selection)
    val header = address.row == 1
    if (header) heading()
    SheetSemantics.formulaState(display)?.let { stateDescription = it }
    val sorted = state.view.sort?.takeIf { header && it.col == address.col }
    if (sorted != null) {
        stateDescription = if (sorted.ascending) TableMessages.SORTED_ASCENDING else TableMessages.SORTED_DESCENDING
    }
    when (display) {
        is CellDisplay.Failed -> error(display.detail)
        is CellDisplay.InvalidFormula -> error(display.detail)
        else -> Unit
    }
    if (!state.readOnly && state.mode == GridMode.Navigate) {
        onClick(label = TableMessages.EDIT_CELL) { SheetSemantics.runCellAction(state, address, TableMessages.EDIT_CELL) }
    }
    val actions = SheetSemantics.cellActions(state, address)
    customActions = if (header) SheetSemantics.headerActions(state, address.col) + actions.filter { a -> SheetSemantics.headerActionLabels(state, address.col).none { it == a.label } } else actions
}
