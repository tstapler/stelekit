package dev.stapler.stelekit.table

/** Inclusive 1-based table-row interval (row 1 is the header, so a sort range starts at 2 or later). */
data class RowRange(val first: Int, val last: Int) {
    init {
        require(first >= 1 && last >= first) { "RowRange needs 1 <= first <= last: $first..$last" }
    }

    operator fun contains(row: Int): Boolean = row in first..last
}

/**
 * One user edit as a value. Anchors are `before` everywhere: `InsertRows(before = N)` makes the new
 * row number N. All row/column numbers are 1-based table coordinates (the delimiter is not a row).
 */
sealed interface TableEdit {
    data class SetCell(val address: CellAddress, val text: CellText) : TableEdit
    data class ClearRange(val range: CellRange) : TableEdit
    data class InsertRows(val before: Int, val count: Int = 1) : TableEdit
    data class InsertCols(val before: Int, val count: Int = 1) : TableEdit
    data class DeleteRows(val at: Int, val count: Int = 1) : TableEdit
    data class DeleteCols(val at: Int, val count: Int = 1) : TableEdit

    /** Moves rows `from until from + count` so they land before original row [before]. */
    data class MoveRows(val from: Int, val count: Int, val before: Int) : TableEdit

    /** Moves columns `from until from + count` so they land before original column [before]. */
    data class MoveCols(val from: Int, val count: Int, val before: Int) : TableEdit
    data class SetAlignment(val col: Int, val align: ColumnAlign) : TableEdit
    data class SortRows(val col: Int, val ascending: Boolean, val rows: RowRange? = null) : TableEdit

    /** Paste [cells] with its top-left at [at]; a single cell over a larger [selection] fills it. */
    data class PasteRange(
        val at: CellAddress,
        val cells: List<List<String>>,
        val selection: CellRange? = null,
    ) : TableEdit

    /**
     * Never issued by callers. [TableEditor] hands this to a [FormulaReferenceTransform] once per
     * formula cell during a sort: that cell's row moved from [fromRow] to [toRow] (ADR-003 own-row rule,
     * the cell's own address is not otherwise passed to the transform).
     */
    data class SortMove(val fromRow: Int, val toRow: Int) : TableEdit
}

/** Why an edit produced no doc; [TableEditOutcome.Refused.count] carries the offending count where one exists. */
enum class RefusalReason {
    HeaderRow,
    LastColumn,
    OutOfRange,
    SortWouldSelfReference,
    SortWouldChangeValues,
    PasteTooLarge,
}

/** Facts about an applied edit; the UI layer turns these into announcements. No strings here. */
sealed interface EditSummary {
    data object NoChange : EditSummary
    data class CellsChanged(val cells: Int) : EditSummary
    data class RowsInserted(val count: Int) : EditSummary
    data class ColsInserted(val count: Int) : EditSummary
    data class RowsDeleted(val count: Int, val formulasBroken: Int) : EditSummary
    data class ColsDeleted(val count: Int, val formulasBroken: Int) : EditSummary

    /** [permutation] lists, per new position, the old index it came from (1-based, over rows or columns). */
    data class Moved(val permutation: List<Int>, val formulasRewritten: Int) : EditSummary
    data class AlignmentSet(val col: Int, val align: ColumnAlign) : EditSummary
    data class Sorted(
        val column: Int,
        val blankCount: Int,
        val rewrittenFormulaCount: Int,
        val crossRowFormulaCount: Int,
    ) : EditSummary

    data class Pasted(
        val rows: Int,
        val cols: Int,
        val addedRows: Int,
        val addedCols: Int,
        val transformedCells: Int,
    ) : EditSummary
}

sealed interface TableEditOutcome {
    data class Applied(val doc: TableDoc, val summary: EditSummary) : TableEditOutcome

    /** A caller commits only on [Applied]; a refusal carries no doc. */
    data class Refused(val reason: RefusalReason, val count: Int = 0) : TableEditOutcome
}

/**
 * Port implemented by `table.formula.RefRewriter`, so `table` never imports `table.formula`.
 * Returns the rewritten text, or null when [cell] is unchanged by [edit] (it must then stay untouched).
 */
fun interface FormulaReferenceTransform {
    fun apply(cell: CellText, edit: TableEdit): CellText?

    companion object {
        val None = FormulaReferenceTransform { _, _ -> null }
    }
}
