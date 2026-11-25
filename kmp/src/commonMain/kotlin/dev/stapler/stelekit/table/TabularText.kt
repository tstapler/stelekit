package dev.stapler.stelekit.table

/** One constant for paste size (ADR-008): parsing refuses above it, and the editor reuses it. */
const val PASTE_CELL_CAP = 10_000

/** Longest single pasted cell, in characters: spreadsheets stop at 32,767, so a longer one is not a cell. */
const val PASTE_CELL_CHARS = 32_767

sealed interface TabularParse {
    /** Rows may be ragged; a trailing line terminator never adds a row. */
    data class Grid(val rows: List<List<String>>) : TabularParse

    /** More than [PASTE_CELL_CAP] cells or a cell over [PASTE_CELL_CHARS] characters; nothing is returned. */
    data object TooLarge : TabularParse
}

/**
 * TSV/CSV codec (RFC-4180-style quoting, Excel/Sheets flavour). Pure, iterative, never throws.
 * Parsing is lenient: an unterminated quote swallows the rest of the input as one cell.
 */
object TabularText {
    const val TAB = '\t'
    const val COMMA = ','

    /** Tab wins when any tab is present, else comma when present, else tab (single column). */
    fun detectDelimiter(text: String): Char = when {
        text.indexOf(TAB) >= 0 -> TAB
        text.indexOf(COMMA) >= 0 -> COMMA
        else -> TAB
    }

    fun parse(text: String, delimiter: Char = detectDelimiter(text)): TabularParse {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var cells = 0
        var i = 0
        val n = text.length
        // true when the input ended right after a row terminator (no pending row)
        var atRowStart = true

        fun endField(): Boolean {
            val fits = field.length <= PASTE_CELL_CHARS
            row.add(field.toString())
            field.setLength(0)
            cells++
            return fits && cells <= PASTE_CELL_CAP
        }

        while (i < n) {
            atRowStart = false
            val c = text[i]
            if (c == '"' && field.isEmpty()) {
                i = readQuoted(text, i + 1, field)
                // After the closing quote, stray characters up to the delimiter stay literal.
                continue
            }
            when {
                c == delimiter -> {
                    if (!endField()) return TabularParse.TooLarge
                    i++
                }
                c == '\r' || c == '\n' -> {
                    if (!endField()) return TabularParse.TooLarge
                    rows.add(row)
                    row = ArrayList()
                    i += if (c == '\r' && i + 1 < n && text[i + 1] == '\n') 2 else 1
                    atRowStart = true
                }
                else -> {
                    field.append(c)
                    i++
                }
            }
        }
        if (!atRowStart) {
            if (!endField()) return TabularParse.TooLarge
            rows.add(row)
        }
        return TabularParse.Grid(rows)
    }

    /** Reads a quoted field body starting after the opening quote; returns the index after the closing quote. */
    private fun readQuoted(text: String, start: Int, out: StringBuilder): Int {
        var i = start
        while (i < text.length) {
            val q = text[i]
            if (q != '"') {
                out.append(q)
                i++
            } else if (i + 1 < text.length && text[i + 1] == '"') {
                out.append('"')
                i += 2
            } else {
                return i + 1
            }
        }
        return i
    }

    /**
     * Rows joined by CRLF with no trailing newline. A row that is a single empty cell is written
     * as `""` so it survives the trailing-newline rule of [parse]. Rows should be non-empty.
     */
    fun format(grid: List<List<String>>, delimiter: Char = TAB): String {
        val sb = StringBuilder()
        grid.forEachIndexed { r, cells ->
            if (r > 0) sb.append("\r\n")
            if (cells.size == 1 && cells[0].isEmpty()) {
                sb.append("\"\"")
                return@forEachIndexed
            }
            cells.forEachIndexed { c, cell ->
                if (c > 0) sb.append(delimiter)
                appendCell(sb, cell, delimiter)
            }
        }
        return sb.toString()
    }

    private fun appendCell(sb: StringBuilder, cell: String, delimiter: Char) {
        val needsQuote = cell.any { it == '"' || it == delimiter || it == '\n' || it == '\r' }
        if (!needsQuote) {
            sb.append(cell)
            return
        }
        sb.append('"')
        for (ch in cell) {
            if (ch == '"') sb.append('"')
            sb.append(ch)
        }
        sb.append('"')
    }
}
