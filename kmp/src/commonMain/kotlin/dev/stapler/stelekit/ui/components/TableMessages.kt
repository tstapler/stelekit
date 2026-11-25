package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.table.formula.CellError

/** Why [dev.stapler.stelekit.table.TableParser] refused a table; mapped from its free-text `Failure.reason`. */
enum class TableParseFailure {
    MissingDelimiterRow,
    Unexpected,
    ;

    companion object {
        fun fromReason(reason: String): TableParseFailure =
            if (reason.startsWith("no header and delimiter")) MissingDelimiterRow else Unexpected
    }
}

/** User-visible table strings (wording from design/ux.md and plan.md); none live in package `table`. */
@Suppress("TooManyFunctions") // one catalogue of user-visible strings, as ux.md lists them
object TableMessages {
    const val CLOSE = "Close"
    const val CANCEL_EDIT = "Cancel edit"
    const val DONE = "Done"
    const val NEXT_COLUMN = "Next column"
    const val LINE_BREAK = "Line break"
    const val SELECT_ROW = "Select row"
    const val SELECT_COLUMN = "Select column"
    const val SELECT_TABLE = "Select table"
    const val EXTEND = "Extend..."
    const val COPY = "Copy"
    const val UNDO = "Undo"
    const val REDO = "Redo"
    const val MAXIMIZE = "Maximize"
    const val MARKDOWN = "Markdown"
    const val EDIT_AS_MARKDOWN = "Edit as markdown"
    const val WHY = "Why?"
    const val EDIT = "Edit"
    const val DISMISS = "Dismiss"
    const val MORE = "More"
    const val RESOLVE = "Resolve"
    const val DISCARDED_EDIT = "Discarded your edit"
    const val COPY_TOO_LARGE = "Selection is too large to copy"
    const val COPY_FAILED = "Could not copy to the clipboard"
    const val EDIT_CELL = "Edit cell"
    const val TIP_SELECT_ROW_COPY = "Select row, then Copy, copies a row"
    const val SHOW_TIPS_AGAIN = "Show tips again"
    const val FIRST_RUN_NOTE = "Tap a cell to edit it. Raw markdown editing is always available."
    const val UNDONE = "Undone"
    const val REDONE = "Redone"
    const val NOTHING_TO_UNDO_TABLE = "Nothing more to undo in this table"
    const val NOTHING_TO_REDO_TABLE = "Nothing more to redo in this table"
    const val NOTHING_TO_UNDO_CELL = "Nothing more to undo in this cell"
    const val NOTHING_TO_REDO_CELL = "Nothing more to redo in this cell"

    // ---- sheet (PR-C 5.1) ----
    const val TABLE_REMOVED = "This table was removed"
    const val NOT_A_TABLE = "This block is no longer a table"
    const val EDITED_ELSEWHERE = "The page was edited elsewhere"
    const val RELOADED_TO_STAY_SAFE = "The table was reloaded from disk to stay safe"
    const val EDITOR_OPENED = "The table was closed because the block is being edited"
    const val SAVE_FAILED_REMOVED = "Your last edit could not be saved: the table was removed"
    const val UPDATED_FROM_DISK = "Updated from disk"
    const val COMMIT_FAILED = "Couldn't save the change"
    const val RETRY = "Retry"
    const val READ_ONLY = "This graph is read-only"
    const val COLUMN_WIDTHS_RESET = "Column widths reset when you close the sheet"
    const val COPY_AS_FORMULAS = "Copy as formulas"
    const val SHORTCUTS = "Keyboard shortcuts"
    const val MORE_OPTIONS = "More options"
    const val SHEET_TITLE_FALLBACK = "Table"
    const val CONFLICT_TITLE = "This table changed while you were editing"
    const val KEEP_MINE = "Keep mine"
    const val TAKE_THEIRS = "Take theirs"
    const val RECOVERED_DRAFT = "Your unsaved edit was kept"
    const val APPLY = "Apply"
    const val DISCARD = "Discard"
    const val DISMISS_NOTICE = "Dismiss notice"
    const val SHEET_UNDO_SCOPE_NOTE = "Undo here only covers edits made in this sheet. Edits made inline earlier: close the sheet and use the page's Ctrl+Z."
    const val RECOVERED_APPLY_DISABLED = "That cell no longer exists"

    // ---- sheet grid (PR-C 5.2, 5.3, 5.8, 5.9) ----
    const val END_OF_TABLE = "End of table"
    const val OPENING_TABLE = "Opening table..."
    const val SAVING = "Saving..."
    const val APPLYING = "Applying..."
    const val STOP_INSERTING = "Stop inserting"
    const val TAP_TO_INSERT_REFERENCE = "Tap a cell to insert its reference"
    const val INSERT_LINE_BREAK = "Insert line break"
    const val READ_ONLY_STATE = "Read only"
    const val EDIT_BAR_LABEL = "Cell contents"
    const val FORMULA_MARKER = "fx"
    const val CIRCULAR_REFERENCE = "Circular reference"

    // ---- sheet clipboard, status, structure, sort (PR-C 5.4 to 5.7) ----
    const val NOTHING_TO_PASTE = "Nothing to paste"
    const val PASTE_TOO_LARGE = "Paste is too large (limit 10,000 cells). Nothing changed."
    const val PASTE_UNREADABLE = "Couldn't read the clipboard as a table"
    const val COPY_FAILED_MANUAL = "Couldn't copy. Select the text and copy manually"
    const val SHOW_AS_TEXT = "Show as text"
    const val PASTE = "Paste"
    const val CUT = "Cut"
    const val CLEAR = "Clear"
    const val PASTE_HERE = "Paste here"
    const val PASTE_HERE_HINT = "Paste the cells here, then press Insert"
    const val INSERT = "Insert"
    const val CANCEL = "Cancel"
    const val SELECT_ALL = "Select all"
    const val LINE_BREAK_STORED = "Line break stored as <br>"
    const val LAST_COLUMN = "A table needs at least one column"
    const val HEADER_ROW_REFUSED = "The header row can't be changed this way"
    const val OUT_OF_RANGE = "That is outside the table"
    const val SORT_SELF_REFERENCE = "Sort would put a total inside its own range"
    const val TOTALS_STAY = "Totals row stays at the bottom"
    const val NOTHING_TO_CLEAR = "Nothing to clear"

    const val SORT_AZ = "Sort rows A-Z (changes file)"
    const val SORT_ZA = "Sort rows Z-A (changes file)"
    const val SORT_SELECTED_AZ = "Sort selected rows A-Z (changes file)"
    const val SORT_SELECTED_ZA = "Sort selected rows Z-A (changes file)"
    const val ALIGN_LEFT = "Align left"
    const val ALIGN_CENTER = "Align center"
    const val ALIGN_RIGHT = "Align right"
    const val INSERT_COLUMN_LEFT = "Insert column left"
    const val INSERT_COLUMN_RIGHT = "Insert column right"
    const val MOVE_COLUMN_LEFT = "Move column left"
    const val MOVE_COLUMN_RIGHT = "Move column right"
    const val DELETE_COLUMN = "Delete column"
    const val INSERT_ROW_ABOVE = "Insert row above"
    const val INSERT_ROW_BELOW = "Insert row below"
    const val MOVE_ROW_UP = "Move row up"
    const val MOVE_ROW_DOWN = "Move row down"
    const val DUPLICATE_ROW = "Duplicate row"
    const val DELETE_ROW = "Delete row"
    const val WIDTH_NARROW = "Narrow"
    const val WIDTH_MEDIUM = "Medium"
    const val WIDTH_WIDE = "Wide"
    const val WIDTH_AUTO_FIT = "Auto-fit"
    const val FREEZE_FIRST_COLUMN = "Freeze first column"
    const val FREEZE_HEADER_ROW = "Freeze header row"
    const val COLUMN_MENU = "Column menu"
    const val ROW_MENU = "Row menu"
    const val RESIZE_COLUMN = "Resize column"
    const val RANGE_START_HANDLE = "Range start handle"
    const val RANGE_END_HANDLE = "Range end handle"
    const val SORTED_ASCENDING = "sorted ascending"
    const val SORTED_DESCENDING = "sorted descending"

    fun pasted(rows: Int, cols: Int, addedRows: Int, addedCols: Int): String {
        val added = listOfNotNull(
            plural(addedRows, "row").takeIf { addedRows > 0 },
            plural(addedCols, "column").takeIf { addedCols > 0 },
        )
        return if (added.isEmpty()) "Pasted $rows x $cols cells" else "Pasted $rows x $cols, added ${added.joinToString(", ")}"
    }

    fun pasteTransformed(cells: Int, pipe: Boolean, lineBreak: Boolean): String {
        val what = listOfNotNull("\"|\" escaped".takeIf { pipe }, LINE_BREAK_STORED.takeIf { lineBreak })
        return "${plural(cells, "cell")} changed" + if (what.isEmpty()) "" else ": " + what.joinToString(". ")
    }

    fun widthLabel(preset: String) = "Width: $preset"

    fun plural(n: Int, word: String): String = "$n $word" + if (n == 1) "" else "s"

    /** "Count 2  Sum 8  Average 4"; an error count is appended as "1 error". */
    fun selectionStats(count: Int, sum: Double, average: Double, errors: Int): String =
        listOfNotNull(
            "Count $count  Sum ${formatStat(sum)}  Average ${formatStat(average)}",
            plural(errors, "error").takeIf { errors > 0 },
        ).joinToString("  ")

    fun errorsOnly(errors: Int): String = plural(errors, "error")

    fun formatStat(d: Double): String {
        if (!d.isFinite()) return d.toString()
        if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
        val text = (kotlin.math.round(d * 1_000_000.0) / 1_000_000.0).toString()
        return if ('E' in text) text else text.trimEnd('0').trimEnd('.')
    }

    fun sorted(column: String, crossRowFormulas: Int, blanks: Int): String =
        "Sorted by $column" +
            (if (crossRowFormulas > 0) ". ${plural(crossRowFormulas, "formula")} ${if (crossRowFormulas == 1) "references" else "reference"} other rows" else "") +
            (if (blanks > 0) ". ${plural(blanks, "blank cell")} sorted last" else "")

    fun sortWouldChangeValues(count: Int) =
        "Sort would change formula results: ${plural(count, "formula")} ${if (count == 1) "references" else "reference"} other rows"

    fun formulasWillBeRewritten(count: Int) = "${plural(count, "formula")} will be rewritten"

    fun referenceOtherRows(count: Int) = "$count reference other rows"

    fun rowsInserted(count: Int) = if (count == 1) "Inserted a row" else "Inserted $count rows"
    fun colsInserted(count: Int) = if (count == 1) "Inserted a column" else "Inserted $count columns"
    fun rowsDeleted(count: Int, broken: Int) = "Deleted ${plural(count, "row")}" + brokenSuffix(broken)
    fun colsDeleted(count: Int, broken: Int) = "Deleted ${plural(count, "column")}" + brokenSuffix(broken)
    fun rowsMoved(count: Int) = if (count == 1) "Moved a row" else "Moved $count rows"
    fun colsMoved(count: Int) = if (count == 1) "Moved a column" else "Moved $count columns"
    const val ROW_DUPLICATED = "Duplicated the row"
    fun cleared(cells: Int) = "Cleared ${plural(cells, "cell")}"
    fun aligned(column: String, align: String) = "Column $column aligned $align"
    fun columnWidth(column: String, dp: Int) = "Column $column set to $dp dp"
    fun columnMenuTitle(column: String) = "Column $column"
    fun rowMenuTitle(row: Int) = "Row $row"
    fun columnHeaderLabel(column: String, sortState: String?) = "Column $column" + (sortState?.let { ", $it" } ?: "")

    private fun brokenSuffix(broken: Int) = if (broken > 0) ". ${plural(broken, "formula")} now ${if (broken == 1) "shows" else "show"} #REF!" else ""

    fun circularReference(path: List<dev.stapler.stelekit.table.CellAddress>): String =
        if (path.isEmpty()) CIRCULAR_REFERENCE else "$CIRCULAR_REFERENCE ${path.joinToString(" -> ")}"

    fun insertedReference(ref: String) = "Inserted $ref. Extend... or type to continue"

    fun moreNotices(count: Int) = "$count more"

    fun recoveredDraft(address: String) = "Your unsaved edit to $address was kept"

    fun applyTo(address: String) = "Apply to $address"

    fun conflictBannerDraft(address: String) = "Your edit to $address is not saved yet"

    fun extendLabel(choice: String) = "Extend selection $choice"

    fun editing(address: String, text: String) = "Editing $address, $text"

    fun heldNotSaved(address: String) = "Your edit to $address is not saved. Resolve it first"

    fun undoLabel(step: String?): String = if (step == null) UNDO else "Undo: $step"
    fun redoLabel(step: String?): String = if (step == null) REDO else "Redo: $step"

    fun copiedVisible(rows: Int, cols: Int): String = "Copied the visible $rows x $cols cells; more rows are not shown here"

    fun copied(rows: Int, cols: Int): String = "Copied $rows x $cols ${if (rows * cols == 1) "cell" else "cells"}"

    fun cannotEditAsGrid(failure: TableParseFailure): String =
        "This table can't be edited as a grid: " + when (failure) {
            TableParseFailure.MissingDelimiterRow -> "missing delimiter row."
            TableParseFailure.Unexpected -> "it could not be read."
        }

    fun showingFirstRows(shown: Int, total: Int): String = "Showing the first $shown of $total rows"

    /** Generic detail for an error cell (S12); position-specific wording needs the evaluator's context. */
    fun errorDetail(error: CellError): String = when (error) {
        CellError.Parse -> "Not a valid formula"
        CellError.Ref -> "Referenced cell is outside the table or was deleted"
        CellError.Cycle -> "Circular reference"
        CellError.DivZero -> "Division by zero"
        CellError.Value -> "A referenced cell is text, expected a number"
        CellError.Num -> "Result is not a valid number"
        CellError.Name -> "Unsupported function. Supported: SUM, AVERAGE, MIN, MAX, COUNT, COUNTA, IF, ROUND, ABS"
        CellError.Limit -> "Formula is too large or nested too deeply"
    }
}
