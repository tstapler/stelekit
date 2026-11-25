package dev.stapler.stelekit.table

/** Column alignment as written in the delimiter row; [NONE] means no `:` marker. */
enum class ColumnAlign { NONE, LEFT, CENTER, RIGHT }

/** Formatting detected from the header line; dirty rows are re-rendered in this style. */
data class TableStyle(
    val indent: String = "",
    val leadPipe: Boolean = true,
    val trailPipe: Boolean = true,
    val pad: String = " ",
    val crlf: Boolean = false,
)

/**
 * One table row. [source] is the verbatim line (without `\r`) while the row is untouched and null once
 * an edit made it dirty. [rawCells] is each cell's source text between pipes (trimmed, escapes intact).
 * Equality compares [cells] only: provenance must not break `parse(serialize(doc)) == doc`.
 */
class RowLine(
    val cells: List<CellText>,
    val rawCells: List<String>,
    val source: String?,
    val cr: Boolean,
) {
    val isDirty: Boolean get() = source == null

    fun withCells(newCells: List<CellText>): RowLine = RowLine(newCells, rawCells, null, cr)

    override fun equals(other: Any?): Boolean = other is RowLine && other.cells == cells
    override fun hashCode(): Int = cells.hashCode()
    override fun toString(): String = "RowLine(${cells.map { it.value }})"

    companion object {
        fun fresh(cells: List<CellText>, cr: Boolean = false) = RowLine(cells, emptyList(), null, cr)
    }
}

/** The `|---|:-:|` line. Not a row: it has no [CellAddress]. Equality compares [aligns] only. */
class DelimiterRow(
    val aligns: List<ColumnAlign>,
    val rawCells: List<String>,
    val source: String?,
    val cr: Boolean,
) {
    fun withAligns(newAligns: List<ColumnAlign>): DelimiterRow = DelimiterRow(newAligns, rawCells, null, cr)

    override fun equals(other: Any?): Boolean = other is DelimiterRow && other.aligns == aligns
    override fun hashCode(): Int = aligns.hashCode()
    override fun toString(): String = "DelimiterRow($aligns)"
}

/**
 * A pipe table plus the verbatim lines around it ([prefix], [suffix] keep any `\r` and a final empty
 * string for a trailing newline). Row 1 is the header; the delimiter is not a row. Equality ignores
 * [style] and per-line provenance.
 */
data class TableDoc(
    val prefix: List<String>,
    val header: RowLine,
    val delimiter: DelimiterRow,
    val body: List<RowLine>,
    val suffix: List<String>,
    val style: TableStyle,
) {
    val rowCount: Int get() = 1 + body.size
    val columnCount: Int get() = header.cells.size

    fun row(row: Int): RowLine = if (row == 1) header else body[row - 2]

    /** 1-based line in the block content for table row [row]; the delimiter sits between rows 1 and 2. */
    fun lineIndexOf(row: Int): Int = prefix.size + if (row == 1) 1 else row + 1

    fun cell(address: CellAddress): CellText? =
        if (address.row > rowCount) null else row(address.row).cells.getOrNull(address.col - 1)

    fun withRow(row: Int, line: RowLine): TableDoc =
        if (row == 1) copy(header = line) else copy(body = body.toMutableList().also { it[row - 2] = line })

    /** Sets one cell, padding a ragged row with empty cells; a no-op when the text is unchanged. */
    fun setCell(address: CellAddress, text: CellText): TableDoc {
        require(address.row in 1..rowCount) { "row ${address.row} outside 1..$rowCount" }
        val line = row(address.row)
        if (line.cells.getOrNull(address.col - 1) == text) return this
        val cells = line.cells.toMutableList()
        while (cells.size < address.col) cells.add(CellText(""))
        cells[address.col - 1] = text
        return withRow(address.row, line.withCells(cells))
    }

    override fun equals(other: Any?): Boolean = other is TableDoc &&
        other.prefix == prefix && other.header == header && other.delimiter == delimiter &&
        other.body == body && other.suffix == suffix

    override fun hashCode(): Int = listOf(prefix, header, delimiter, body, suffix).hashCode()
}

/** Cell escaping shared by parser and serializer: `\|` is a pipe, `<br>` variants are newlines. */
object TableCellCodec {
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

    /** Resolves only `\|`, left to right (`\\|` is a backslash then an escaped pipe). */
    fun unescapePipes(raw: String): String = raw.replace("\\|", "|")

    fun unescape(raw: String): String = BR.replace(unescapePipes(raw), "\n")

    fun escape(text: String): String {
        val s = text.replace("\r\n", "\n").replace('\r', '\n')
            .replace("|", "\\|").replace("\n", "<br>")
        return guardTrailingBackslash(s)
    }

    /** A trailing backslash would otherwise escape the closing pipe (raw cells lose the padding that protected it). */
    fun guardTrailingBackslash(s: String): String = if (s.endsWith("\\")) "$s " else s

    fun alignOf(cell: String): ColumnAlign {
        val c = cell.trim()
        if (c.length < 2) return ColumnAlign.NONE
        val left = c.startsWith(':')
        val right = c.endsWith(':')
        return when {
            left && right -> ColumnAlign.CENTER
            right -> ColumnAlign.RIGHT
            left -> ColumnAlign.LEFT
            else -> ColumnAlign.NONE
        }
    }

    fun delimiterCell(align: ColumnAlign): String = when (align) {
        ColumnAlign.NONE -> "---"
        ColumnAlign.LEFT -> ":--"
        ColumnAlign.CENTER -> ":-:"
        ColumnAlign.RIGHT -> "--:"
    }
}
