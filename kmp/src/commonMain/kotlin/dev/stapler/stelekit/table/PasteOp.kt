package dev.stapler.stelekit.table

/** Whole-table size cap after a paste grows it (UX-26); the paste itself is capped by [PASTE_CELL_CAP]. */
const val TABLE_CELL_CAP = 20_000

internal object PasteOp {
    fun apply(doc: TableDoc, edit: TableEdit.PasteRange): TableEditOutcome {
        val grid = edit.cells
        if (grid.isEmpty() || grid.all { it.isEmpty() }) return TableEditOutcome.Applied(doc, EditSummary.NoChange)
        val origin = edit.at
        if (origin.row > doc.rowCount || origin.col > doc.columnCount) return TableEditOutcome.Refused(RefusalReason.OutOfRange)

        val sel = edit.selection
        val fill = sel != null && grid.size == 1 && grid[0].size == 1 && (sel.rows > 1 || sel.cols > 1)
        val top = if (fill) sel!!.topLeft else origin
        val rows = if (fill) sel!!.rows else grid.size
        val cols = if (fill) sel!!.cols else grid.maxOf { it.size }
        if (rows.toLong() * cols > PASTE_CELL_CAP) return TableEditOutcome.Refused(RefusalReason.PasteTooLarge)

        val needRows = top.row + rows - 1
        val needCols = top.col + cols - 1
        val addedRows = maxOf(0, needRows - doc.rowCount)
        val addedCols = maxOf(0, needCols - doc.columnCount)
        val newRows = doc.rowCount + addedRows
        val newCols = doc.columnCount + addedCols
        if (newRows.toLong() * newCols > TABLE_CELL_CAP) return TableEditOutcome.Refused(RefusalReason.PasteTooLarge)

        var next = grow(doc, addedRows, addedCols)
        var transformed = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val source = if (fill) grid[0][0] else grid[r].getOrNull(c) ?: continue
                val text = source.replace("\r\n", "\n").replace('\r', '\n')
                if ('|' in text || '\n' in text) transformed++
                next = next.setCell(CellAddress(top.row + r, top.col + c), CellText(text))
            }
        }
        return TableEditOutcome.Applied(next, EditSummary.Pasted(rows, cols, addedRows, addedCols, transformed))
    }

    private fun grow(doc: TableDoc, addedRows: Int, addedCols: Int): TableDoc {
        var next = doc
        if (addedCols > 0) {
            val width = doc.columnCount + addedCols
            fun widen(line: RowLine): RowLine =
                line.withCells(line.cells + List((width - line.cells.size).coerceAtLeast(0)) { CellText("") })
            val aligns = doc.delimiter.aligns + List((width - doc.delimiter.aligns.size).coerceAtLeast(0)) { ColumnAlign.NONE }
            next = next.copy(
                header = widen(next.header),
                delimiter = next.delimiter.withAligns(aligns),
                body = next.body.map { widen(it) },
            )
        }
        if (addedRows > 0) {
            val blank = List(next.columnCount) { CellText("") }
            next = next.copy(body = next.body + List(addedRows) { RowLine.fresh(blank, next.header.cr) })
        }
        return next
    }
}
