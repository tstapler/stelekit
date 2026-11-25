// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellText
import dev.stapler.stelekit.table.ColumnAlign
import dev.stapler.stelekit.table.EditSummary
import dev.stapler.stelekit.table.GridCommand
import dev.stapler.stelekit.table.GridDirection
import dev.stapler.stelekit.table.GridSelection
import dev.stapler.stelekit.table.NoticeType
import dev.stapler.stelekit.table.RefusalReason
import dev.stapler.stelekit.table.RowRange
import dev.stapler.stelekit.table.SortTotals
import dev.stapler.stelekit.table.TableDoc
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.table.TableEditOutcome
import dev.stapler.stelekit.ui.components.TableMessages

/**
 * The sheet's commands that change the table or its view: clipboard keys, insert, delete, move, duplicate, align,
 * sort, width presets and freeze (Stories 5.4 to 5.7). Each table change is one [SheetState.attemptEdit], so it is one
 * host commit and one undo step; a refusal becomes a notice and writes nothing. It is the [SheetCommandSink] the body
 * installs, and the one place the menus, the strip and the keys call.
 */
@Suppress("TooManyFunctions") // one command surface for keys, menus and the strip; each function is a one-step command
internal class SheetCommands(
    private val state: SheetState,
    private val session: TableSheetSession,
    val clipboard: SheetClipboard,
) : SheetCommandSink {

    override fun onCommand(command: GridCommand, selection: GridSelection): Boolean = when (command) {
        GridCommand.Copy -> true.also { clipboard.copy() }
        GridCommand.Cut -> true.also { clipboard.cut() }
        GridCommand.Paste -> true.also { clipboard.paste() }
        GridCommand.InsertRowOrColumn -> true.also { insertAtSelection() }
        GridCommand.DeleteRowOrColumn -> true.also { deleteAtSelection() }
        GridCommand.OpenContextMenu -> true.also { openContextMenu() }
        is GridCommand.MoveSelection -> true.also { moveSelection(command.dir) }
        else -> false
    }

    // ---- keys ----------------------------------------------------------------------------------------------------

    /** Ctrl+Shift+=: columns for a column selection, otherwise rows above the selection (below the header from row 1). */
    fun insertAtSelection() {
        val sel = state.selection
        val r = sel.range
        if (sel.kind == GridSelection.Kind.Columns) insertCols(r.topLeft.col, r.cols) else insertRows(maxOf(2, r.topLeft.row), r.rows)
    }

    /** Ctrl+-: the selected columns for a column selection, otherwise the selected rows. */
    fun deleteAtSelection() {
        val sel = state.selection
        val r = sel.range
        if (sel.kind == GridSelection.Kind.Columns) deleteCols(r.topLeft.col, r.cols) else deleteRows(r.topLeft.row, r.rows)
    }

    private fun openContextMenu() {
        val sel = state.selection
        state.openMenu(
            when (sel.kind) {
                GridSelection.Kind.Rows -> SheetMenuTarget.Row(sel.range.topLeft.row)
                GridSelection.Kind.Columns -> SheetMenuTarget.Column(sel.range.topLeft.col)
                else -> SheetMenuTarget.Cell(sel.anchor)
            },
        )
    }

    /** Alt+Shift+Arrow: up and down move the selected rows, left and right the selected columns. */
    fun moveSelection(dir: GridDirection) {
        val r = state.selection.range
        when (dir) {
            GridDirection.Up -> moveRows(r.topLeft.row, r.rows, up = true)
            GridDirection.Down -> moveRows(r.topLeft.row, r.rows, up = false)
            GridDirection.Left -> moveCols(r.topLeft.col, r.cols, left = true)
            GridDirection.Right -> moveCols(r.topLeft.col, r.cols, left = false)
        }
    }

    // ---- structure -----------------------------------------------------------------------------------------------

    fun insertRows(before: Int, count: Int = 1) {
        val col = state.selection.anchor.col
        run(TableEdit.InsertRows(before, count), "insert ${TableMessages.plural(count, "row")}") { s ->
            state.announce(TableMessages.rowsInserted((s as? EditSummary.RowsInserted)?.count ?: count))
            state.selectRange(GridSelection.single(CellAddress(before, col), state.bounds))
        }
    }

    fun insertCols(before: Int, count: Int = 1) {
        val row = state.selection.anchor.row
        run(TableEdit.InsertCols(before, count), "insert ${TableMessages.plural(count, "column")}") { s ->
            state.announce(TableMessages.colsInserted((s as? EditSummary.ColsInserted)?.count ?: count))
            state.selectRange(GridSelection.single(CellAddress(row, before), state.bounds))
        }
    }

    fun deleteRows(at: Int, count: Int = 1) {
        run(TableEdit.DeleteRows(at, count), "delete ${TableMessages.plural(count, "row")}") { s ->
            val message = TableMessages.rowsDeleted(count, (s as? EditSummary.RowsDeleted)?.formulasBroken ?: 0)
            state.announce(message)
            session.noticeWithUndo(message)
        }
    }

    fun deleteCols(at: Int, count: Int = 1) {
        run(TableEdit.DeleteCols(at, count), "delete ${TableMessages.plural(count, "column")}") { s ->
            val message = TableMessages.colsDeleted(count, (s as? EditSummary.ColsDeleted)?.formulasBroken ?: 0)
            state.announce(message)
            session.noticeWithUndo(message)
        }
    }

    /** Moves [count] rows from [from] one step; stops quietly at the header row or the last row. */
    @Suppress("FlagArgument") // the direction is the one thing the two callers differ by
    fun moveRows(from: Int, count: Int, up: Boolean) {
        val last = from + count - 1
        if (up && from <= 2) return state.announce(if (from < 2) TableMessages.HEADER_ROW_REFUSED else TableMessages.END_OF_TABLE)
        if (!up && last >= state.rowCount) return state.announce(TableMessages.END_OF_TABLE)
        val before = if (up) from - 1 else last + 2
        val sel = state.selection
        run(TableEdit.MoveRows(from, count, before), "move ${TableMessages.plural(count, "row")}") {
            val delta = if (up) -1 else 1
            state.selectRange(shifted(sel, delta, 0))
            state.announce(TableMessages.rowsMoved(count))
        }
    }

    @Suppress("FlagArgument")
    fun moveCols(from: Int, count: Int, left: Boolean) {
        val last = from + count - 1
        if (left && from <= 1) return state.announce(TableMessages.END_OF_TABLE)
        if (!left && last >= state.columnCount) return state.announce(TableMessages.END_OF_TABLE)
        val before = if (left) from - 1 else last + 2
        val sel = state.selection
        run(TableEdit.MoveCols(from, count, before), "move ${TableMessages.plural(count, "column")}") {
            state.selectRange(shifted(sel, 0, if (left) -1 else 1))
            state.announce(TableMessages.colsMoved(count))
        }
    }

    private fun shifted(sel: GridSelection, rows: Int, cols: Int): GridSelection {
        fun move(a: CellAddress) = CellAddress((a.row + rows).coerceAtLeast(1), (a.col + cols).coerceAtLeast(1))
        return GridSelection(move(sel.anchor), move(sel.focus), sel.kind)
    }

    /** A copy of [row] right below it, in one commit; formula text is copied as written. */
    fun duplicateRow(row: Int) {
        val d = state.doc ?: return
        if (row < 2) return refuse(RefusalReason.HeaderRow, 0)
        val inserted = (state.previewEdit(TableEdit.InsertRows(row + 1)) as? TableEditOutcome.Applied)?.doc ?: return
        val source = d.row(row).cells
        val target = inserted.row(row + 1)
        val filled = inserted.withRow(row + 1, target.withCells(List(inserted.columnCount) { source.getOrElse(it) { CellText("") } }))
        when (state.commitPrepared(filled, TableEdit.InsertRows(row + 1), "duplicate row $row")) {
            is EditAttempt.Applied -> {
                state.announce(TableMessages.ROW_DUPLICATED)
                state.selectRange(GridSelection.single(CellAddress(row + 1, state.selection.anchor.col), state.bounds))
            }
            else -> Unit
        }
    }

    fun clearSelection() {
        val r = state.selection.range
        val attempt = state.attemptEdit(TableEdit.ClearRange(r), "clear ${r.topLeft}")
        if (attempt is EditAttempt.Applied) state.announce(TableMessages.cleared(r.rows * r.cols))
        else if (attempt == EditAttempt.NoChange) state.announce(TableMessages.NOTHING_TO_CLEAR)
    }

    // ---- alignment, width, freeze --------------------------------------------------------------------------------

    /** Writes only the column's delimiter cell (`---:`); one undo step. */
    fun align(col: Int, align: ColumnAlign) {
        val label = alignLabel(align)
        run(TableEdit.SetAlignment(col, align), "align ${CellAddress.columnLabel(col)} $label") {
            state.announce(TableMessages.aligned(CellAddress.columnLabel(col), label))
        }
    }

    private fun alignLabel(align: ColumnAlign) = when (align) {
        ColumnAlign.LEFT -> "left"
        ColumnAlign.CENTER -> "center"
        ColumnAlign.RIGHT -> "right"
        ColumnAlign.NONE -> "to default"
    }

    /** A width preset: session view state only, never a table write. */
    fun applyWidth(col: Int, preset: WidthPreset) {
        val d = state.doc ?: return
        state.resizeColumn(col, ColumnWidths.widthFor(preset, d, col, state::valueText))
        state.announce(TableMessages.columnWidth(CellAddress.columnLabel(col), state.view.widthOf(col).toInt()))
    }

    fun toggleFreezeFirstColumn() = state.updateView(state.view.withFreezeFirstColumn(!state.view.freezeFirstColumn))

    fun toggleFreezeHeaderRow() = state.updateView(state.view.withFreezeHeaderRow(!state.view.freezeHeaderRow))

    // ---- sort ----------------------------------------------------------------------------------------------------

    /** The rows a sort moves: the selected body rows for a row-wise range, otherwise null (the whole body minus totals). */
    fun sortRange(): RowRange? {
        val sel = state.selection
        if (sel.kind == GridSelection.Kind.Columns || sel.kind == GridSelection.Kind.Table) return null
        val r = sel.range
        if (r.rows < 2) return null
        val top = maxOf(2, r.topLeft.row)
        return if (r.bottomRight.row >= top) RowRange(top, r.bottomRight.row) else null
    }

    fun columnName(col: Int): String =
        state.doc?.cell(CellAddress(1, col))?.value?.trim()?.takeIf { it.isNotEmpty() } ?: CellAddress.columnLabel(col)

    fun sort(col: Int, ascending: Boolean) {
        val name = columnName(col)
        val attempt = state.attemptEdit(TableEdit.SortRows(col, ascending, sortRange()), "sort by $name")
        when (attempt) {
            is EditAttempt.Applied -> {
                val s = attempt.outcome.summary as? EditSummary.Sorted
                state.updateView(state.view.withSort(col, ascending))
                val message = TableMessages.sorted(name, s?.crossRowFormulaCount ?: 0, s?.blankCount ?: 0)
                state.announce(message)
                session.noticeWithUndo(message)
            }
            is EditAttempt.Refused -> refuse(attempt.reason, attempt.count)
            EditAttempt.NoChange -> state.announce(TableMessages.sorted(name, 0, 0))
            EditAttempt.NotCommitted -> Unit
        }
    }

    /** What the sort will do, from the same pure edit run without committing (pre-mortem P2-3). */
    fun sortHelper(col: Int, ascending: Boolean): List<String> {
        val d = state.doc ?: return emptyList()
        val lines = ArrayList<String>()
        when (val outcome = state.previewEdit(TableEdit.SortRows(col, ascending, sortRange()))) {
            is TableEditOutcome.Applied -> (outcome.summary as? EditSummary.Sorted)?.let { s ->
                if (s.rewrittenFormulaCount > 0) lines += TableMessages.formulasWillBeRewritten(s.rewrittenFormulaCount)
                if (s.crossRowFormulaCount > 0) lines += TableMessages.referenceOtherRows(s.crossRowFormulaCount)
            }
            is TableEditOutcome.Refused -> lines += refusalText(outcome.reason, outcome.count)
            null -> Unit
        }
        if (sortRange() == null && SortTotals.hasTrailingTotals(d)) lines += TableMessages.TOTALS_STAY
        return lines
    }

    // ---- refusals and the runner -----------------------------------------------------------------------------------

    private fun run(edit: TableEdit, label: String, onApplied: (EditSummary) -> Unit) {
        when (val attempt = state.attemptEdit(edit, label)) {
            is EditAttempt.Applied -> onApplied(attempt.outcome.summary)
            is EditAttempt.Refused -> refuse(attempt.reason, attempt.count)
            EditAttempt.NoChange, EditAttempt.NotCommitted -> Unit
        }
    }

    private fun refuse(reason: RefusalReason, count: Int) {
        val text = refusalText(reason, count)
        if (reason == RefusalReason.SortWouldChangeValues) {
            session.notices.withAction(NoticeType.Action, text, TableMessages.EDIT_AS_MARKDOWN) { session.editAsMarkdown() }
        } else {
            session.notices.info(text)
        }
    }

    private fun refusalText(reason: RefusalReason, count: Int): String = when (reason) {
        RefusalReason.LastColumn -> TableMessages.LAST_COLUMN
        RefusalReason.HeaderRow -> TableMessages.HEADER_ROW_REFUSED
        RefusalReason.OutOfRange -> TableMessages.OUT_OF_RANGE
        RefusalReason.SortWouldSelfReference -> TableMessages.SORT_SELF_REFERENCE
        RefusalReason.SortWouldChangeValues -> TableMessages.sortWouldChangeValues(count)
        RefusalReason.PasteTooLarge -> TableMessages.PASTE_TOO_LARGE
    }

    // ---- menus ---------------------------------------------------------------------------------------------------

    override fun menuFor(target: SheetMenuTarget): SheetMenuModel? {
        val d = state.doc ?: return null
        return when (target) {
            is SheetMenuTarget.Column -> columnMenu(d, target.col)
            is SheetMenuTarget.Row -> rowMenu(d, target.row)
            is SheetMenuTarget.Cell -> cellMenu(d, target.address)
        }
    }

    /** Read-only disables every entry with that reason; otherwise [reason] explains a disabled one. */
    @Suppress("LongParameterList") // positional by design: the menu tables below read as one row per entry
    private fun item(id: String, label: String, enabled: Boolean, reason: String?, helper: List<String>, checked: Boolean?, action: () -> Unit): SheetMenuItem {
        val off = if (state.readOnly) TableMessages.READ_ONLY else null
        return SheetMenuItem(id, label, action, enabled && off == null, off ?: reason.takeIf { !enabled }, helper, checked)
    }

    private fun columnMenu(d: TableDoc, col: Int): SheetMenuModel {
        val picked = sortRange() != null
        val aligns = d.delimiter.aligns
        val current = aligns.getOrElse(col - 1) { ColumnAlign.NONE }
        val last = d.columnCount
        return SheetMenuModel(
            TableMessages.columnMenuTitle(CellAddress.columnLabel(col)),
            listOf(
                item("sortAsc", if (picked) TableMessages.SORT_SELECTED_AZ else TableMessages.SORT_AZ, true, null, sortHelper(col, true), null) { sort(col, true) },
                item("sortDesc", if (picked) TableMessages.SORT_SELECTED_ZA else TableMessages.SORT_ZA, true, null, sortHelper(col, false), null) { sort(col, false) },
                item("alignLeft", TableMessages.ALIGN_LEFT, true, null, emptyList(), current == ColumnAlign.LEFT) { align(col, ColumnAlign.LEFT) },
                item("alignCenter", TableMessages.ALIGN_CENTER, true, null, emptyList(), current == ColumnAlign.CENTER) { align(col, ColumnAlign.CENTER) },
                item("alignRight", TableMessages.ALIGN_RIGHT, true, null, emptyList(), current == ColumnAlign.RIGHT) { align(col, ColumnAlign.RIGHT) },
                item("insertLeft", TableMessages.INSERT_COLUMN_LEFT, true, null, emptyList(), null) { insertCols(col) },
                item("insertRight", TableMessages.INSERT_COLUMN_RIGHT, true, null, emptyList(), null) { insertCols(col + 1) },
                item("moveLeft", TableMessages.MOVE_COLUMN_LEFT, col > 1, null, emptyList(), null) { moveCols(col, 1, left = true) },
                item("moveRight", TableMessages.MOVE_COLUMN_RIGHT, col < last, null, emptyList(), null) { moveCols(col, 1, left = false) },
                item("deleteColumn", TableMessages.DELETE_COLUMN, last > 1, TableMessages.LAST_COLUMN, emptyList(), null) { deleteCols(col) },
            ) + WidthPreset.entries.map { p ->
                item("width${p.name}", TableMessages.widthLabel(p.label), true, null, emptyList(), null) { applyWidth(col, p) }
            } + listOf(
                item("freezeFirstColumn", TableMessages.FREEZE_FIRST_COLUMN, true, null, emptyList(), state.view.freezeFirstColumn) { toggleFreezeFirstColumn() },
                item("freezeHeaderRow", TableMessages.FREEZE_HEADER_ROW, true, null, emptyList(), state.view.freezeHeaderRow) { toggleFreezeHeaderRow() },
            ),
        )
    }

    private fun rowMenu(d: TableDoc, row: Int): SheetMenuModel {
        val header = row < 2
        val headerReason = TableMessages.HEADER_ROW_REFUSED
        return SheetMenuModel(
            TableMessages.rowMenuTitle(row),
            listOf(
                item("insertAbove", TableMessages.INSERT_ROW_ABOVE, !header, headerReason, emptyList(), null) { insertRows(row) },
                item("insertBelow", TableMessages.INSERT_ROW_BELOW, true, null, emptyList(), null) { insertRows(row + 1) },
                item("moveUp", TableMessages.MOVE_ROW_UP, row > 2, headerReason, emptyList(), null) { moveRows(row, 1, up = true) },
                item("moveDown", TableMessages.MOVE_ROW_DOWN, !header && row < d.rowCount, headerReason, emptyList(), null) { moveRows(row, 1, up = false) },
                item("duplicateRow", TableMessages.DUPLICATE_ROW, !header, headerReason, emptyList(), null) { duplicateRow(row) },
                item("deleteRow", TableMessages.DELETE_ROW, !header, headerReason, emptyList(), null) { deleteRows(row) },
            ),
        )
    }

    private fun cellMenu(d: TableDoc, at: CellAddress): SheetMenuModel {
        val header = at.row < 2
        return SheetMenuModel(
            "Cell $at",
            listOf(
                item("copy", TableMessages.COPY, true, null, emptyList(), null) { clipboard.copy() },
                item("cut", TableMessages.CUT, true, null, emptyList(), null) { clipboard.cut() },
                item("paste", TableMessages.PASTE, true, null, emptyList(), null) { clipboard.paste() },
                item("clear", TableMessages.CLEAR, true, null, emptyList(), null) { clearSelection() },
                item("insertAbove", TableMessages.INSERT_ROW_ABOVE, !header, TableMessages.HEADER_ROW_REFUSED, emptyList(), null) { insertRows(at.row) },
                item("insertBelow", TableMessages.INSERT_ROW_BELOW, true, null, emptyList(), null) { insertRows(at.row + 1) },
                item("insertLeft", TableMessages.INSERT_COLUMN_LEFT, true, null, emptyList(), null) { insertCols(at.col) },
                item("insertRight", TableMessages.INSERT_COLUMN_RIGHT, true, null, emptyList(), null) { insertCols(at.col + 1) },
                item("deleteRow", TableMessages.DELETE_ROW, !header, TableMessages.HEADER_ROW_REFUSED, emptyList(), null) { deleteRows(at.row) },
                item("deleteColumn", TableMessages.DELETE_COLUMN, d.columnCount > 1, TableMessages.LAST_COLUMN, emptyList(), null) { deleteCols(at.col) },
            ),
        )
    }
}
