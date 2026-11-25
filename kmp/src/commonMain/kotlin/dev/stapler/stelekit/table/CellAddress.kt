package dev.stapler.stelekit.table

/** Unescaped text of one cell; a formula is just a CellText starting with `=`. */
@kotlin.jvm.JvmInline
value class CellText(val value: String)

/** 1-based table coordinates. Row 1 is the header row; the delimiter line is not a row. Prints as `B2`. */
data class CellAddress(val row: Int, val col: Int) {
    init {
        require(row >= 1 && col >= 1) { "CellAddress is 1-based: row=$row col=$col" }
    }

    override fun toString(): String = columnLabel(col) + row

    companion object {
        fun columnLabel(col: Int): String {
            var n = col
            val sb = StringBuilder()
            while (n > 0) {
                val r = (n - 1) % 26
                sb.append('A' + r)
                n = (n - 1) / 26
            }
            return sb.reverse().toString()
        }
    }
}

/** Normalized rectangle: [topLeft] is the minimum row and column, [bottomRight] the maximum. */
data class CellRange(val topLeft: CellAddress, val bottomRight: CellAddress) {
    val rows: Int get() = bottomRight.row - topLeft.row + 1
    val cols: Int get() = bottomRight.col - topLeft.col + 1

    operator fun contains(a: CellAddress): Boolean =
        a.row in topLeft.row..bottomRight.row && a.col in topLeft.col..bottomRight.col

    companion object {
        fun of(a: CellAddress, b: CellAddress): CellRange = CellRange(
            CellAddress(minOf(a.row, b.row), minOf(a.col, b.col)),
            CellAddress(maxOf(a.row, b.row), maxOf(a.col, b.col)),
        )
    }
}
