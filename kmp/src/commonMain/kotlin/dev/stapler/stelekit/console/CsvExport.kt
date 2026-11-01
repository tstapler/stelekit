// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

/**
 * RFC 4180 CSV for a [ResultTable] with OWASP CSV-injection defence: a cell a spreadsheet would
 * read as a formula is prefixed with a single quote. The prefix changes the data, which is the
 * accepted trade for opening an export in Excel or Sheets safely. NULL becomes an empty field.
 */
fun ResultTable.toCsv(): String {
    val out = StringBuilder()
    fun line(cells: List<String?>) {
        cells.forEachIndexed { i, c ->
            if (i > 0) out.append(',')
            out.append(CsvEscape.field(c))
        }
        out.append("\r\n")
    }
    line(columns)
    rows.forEach { line(it) }
    return out.toString()
}

object CsvEscape {
    private val NUMBER = Regex("-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?")
    private val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t', '\r')

    /** The text a spreadsheet will see for [value]: formula starters get a leading apostrophe; plain negative numbers do not. */
    fun neutralize(value: String): String {
        if (value.isEmpty()) return value
        val risky = value[0] in FORMULA_STARTS && !NUMBER.matches(value)
        return if (risky) "'$value" else value
    }

    fun field(value: String?): String {
        val text = neutralize(value ?: return "")
        val needsQuotes = text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }
}
