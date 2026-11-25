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

    fun extendLabel(choice: String) = "Extend selection $choice"

    fun editing(address: String, text: String) = "Editing $address, $text"

    fun heldNotSaved(address: String) = "Your edit to $address is not saved. Resolve it first"

    fun undoLabel(step: String?): String = if (step == null) UNDO else "Undo: $step"
    fun redoLabel(step: String?): String = if (step == null) REDO else "Redo: $step"

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
