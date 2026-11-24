package dev.stapler.stelekit.table

/** Table size as plain ints so selection code never depends on the document type. Both are at least 1. */
data class GridBounds(val rows: Int, val cols: Int) {
    init {
        require(rows >= 1 && cols >= 1) { "GridBounds needs at least one row and column: $rows x $cols" }
    }

    fun clamp(a: CellAddress): CellAddress = CellAddress(a.row.coerceIn(1, rows), a.col.coerceIn(1, cols))
}

/**
 * Anchor/focus selection (ADR-005). The anchor is the active cell; the focus is the moving end of a range.
 * Every factory and mutator clamps to the given [GridBounds], so a selection never leaves the grid.
 */
data class GridSelection(
    val anchor: CellAddress,
    val focus: CellAddress = anchor,
    val kind: Kind = Kind.Cells,
) {
    enum class Kind { Cells, Rows, Columns, Table }

    val range: CellRange get() = CellRange.of(anchor, focus)
    val cellCount: Int get() = range.rows * range.cols
    val isSingleCell: Boolean get() = anchor == focus

    operator fun contains(a: CellAddress): Boolean = a in range

    /** Collapses to the active cell. */
    fun collapse(): GridSelection = GridSelection(anchor)

    fun extendTo(target: CellAddress, bounds: GridBounds): GridSelection =
        GridSelection(bounds.clamp(anchor), bounds.clamp(target), Kind.Cells)

    fun clamp(bounds: GridBounds): GridSelection =
        GridSelection(bounds.clamp(anchor), bounds.clamp(focus), kind)

    /** Whole columns spanned by the current range, rows 1 to last. */
    fun selectColumns(bounds: GridBounds): GridSelection {
        val r = clamp(bounds).range
        return GridSelection(
            CellAddress(1, r.topLeft.col), CellAddress(bounds.rows, r.bottomRight.col), Kind.Columns,
        )
    }

    /** Whole rows spanned by the current range, columns 1 to last. */
    fun selectRows(bounds: GridBounds): GridSelection {
        val r = clamp(bounds).range
        return GridSelection(
            CellAddress(r.topLeft.row, 1), CellAddress(r.bottomRight.row, bounds.cols), Kind.Rows,
        )
    }

    companion object {
        fun single(a: CellAddress, bounds: GridBounds): GridSelection = GridSelection(bounds.clamp(a))

        fun table(bounds: GridBounds): GridSelection =
            GridSelection(CellAddress(1, 1), CellAddress(bounds.rows, bounds.cols), Kind.Table)
    }
}
