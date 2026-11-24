package dev.stapler.stelekit.table

/** COUNT / SUM / AVERAGE of the numeric cells in a selection (status line). */
data class SelectionStatsResult(val count: Int, val sum: Double, val average: Double)

/**
 * Takes a value lookup instead of a calculator so package `table` never imports `table.formula`
 * (pass `TableCalc::numberAt` and `TableCalc::displayAt` / `rawAt`).
 */
object SelectionStats {
    /** Null when the selection holds no numbers, so the status line can hide the stats. */
    fun compute(selection: GridSelection, numberAt: (CellAddress) -> Double?): SelectionStatsResult? {
        var count = 0
        var sum = 0.0
        cells(selection.range).forEach { a -> numberAt(a)?.let { count++; sum += it } }
        return if (count == 0) null else SelectionStatsResult(count, sum, sum / count)
    }

    /** The selection as TSV (CRLF rows, quoted where needed) using [textAt] for each cell. */
    fun toTsv(selection: GridSelection, textAt: (CellAddress) -> String): String {
        val r = selection.range
        val grid = (r.topLeft.row..r.bottomRight.row).map { row ->
            (r.topLeft.col..r.bottomRight.col).map { col -> textAt(CellAddress(row, col)) }
        }
        return TabularText.format(grid)
    }

    private fun cells(r: CellRange): Sequence<CellAddress> = sequence {
        for (row in r.topLeft.row..r.bottomRight.row) for (col in r.topLeft.col..r.bottomRight.col) {
            yield(CellAddress(row, col))
        }
    }
}
