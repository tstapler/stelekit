// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

/**
 * One finished `sql` result as the grid shows it. Bounded by construction: the reader caps rows and
 * cells, and [cappedCell] re-applies the cell cap so a table built elsewhere cannot exceed it.
 *
 * [explainSql] is the statement the EXPLAIN QUERY PLAN chip would analyse; null when the chip does
 * not apply (the query finished within its limits, or it already is an EXPLAIN).
 */
data class ResultTable(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val truncatedAtRows: Int? = null,
    val explainSql: String? = null,
) {
    val truncated: Boolean get() = truncatedAtRows != null

    /** The same lines the console printed before the grid existed; also the export text. */
    fun toText(): String {
        if (columns.isEmpty()) return ""
        val header = columns.joinToString(" | ")
        val rule = columns.joinToString("-+-") { "-".repeat(it.length.coerceAtLeast(3)) }
        val body = rows.map { row -> row.joinToString(" | ") { it?.replace('\n', ' ')?.replace('\r', ' ') ?: "NULL" } }
        return (listOf(header, rule) + body).joinToString("\n")
    }

    /** Applies [transform] to every non-null cell (used for redaction); shape and flags are kept. */
    fun mapCells(transform: (String) -> String): ResultTable =
        copy(columns = columns.map(transform), rows = rows.map { row -> row.map { it?.let(transform) } })

    companion object {
        const val MAX_CELL_CHARS = 2_048

        fun cappedCell(value: String?, max: Int = MAX_CELL_CHARS): String? =
            if (value == null || value.length <= max) value else value.take(max) + "…"

        /** The command line the EXPLAIN chip puts in the input: quoted so the statement survives tokenizing. */
        fun explainCommandLine(statement: String): String =
            "sql " + CommandLineTokenizer.quote("EXPLAIN QUERY PLAN $statement")

        /** The statement the EXPLAIN chip analyses, or null if one does not apply (already an EXPLAIN, PRAGMA, ...). */
        fun explainTarget(sql: String): String? {
            val verb = sql.trimStart().takeWhile { it.isLetter() }.lowercase()
            return sql.trim().trimEnd(';').takeIf { verb == "select" || verb == "with" || verb == "values" }
        }
    }
}

/** Layout and accessibility text for the grid, kept out of Compose so it is unit-testable. */
object ResultGridLayout {
    const val MIN_COLUMN_CHARS = 6
    const val MAX_COLUMN_CHARS = 40
    const val SAMPLE_ROWS = 100
    private const val DESCRIPTION_CELL_CHARS = 80

    /** Width in characters per column: the longest of the header and the first [SAMPLE_ROWS] cells, clamped. */
    fun columnChars(table: ResultTable): List<Int> = table.columns.mapIndexed { index, name ->
        val longest = table.rows.asSequence().take(SAMPLE_ROWS).maxOfOrNull { (it.getOrNull(index) ?: "NULL").length } ?: 0
        maxOf(name.length, longest).coerceIn(MIN_COLUMN_CHARS, MAX_COLUMN_CHARS)
    }

    /** What TalkBack reads for one data row: position, then `column: value` pairs. [rowIndex] is 0-based. */
    fun rowDescription(table: ResultTable, rowIndex: Int): String {
        val row = table.rows[rowIndex]
        val cells = table.columns.mapIndexed { i, name -> "$name: ${describeCell(row.getOrNull(i))}" }
        return "Row ${rowIndex + 1} of ${table.rows.size}, ${cells.joinToString(", ")}"
    }

    fun headerDescription(table: ResultTable): String =
        "Columns ${table.columns.size}: ${table.columns.joinToString(", ")}"

    fun summary(table: ResultTable): String {
        val cap = table.truncatedAtRows?.let { ", truncated at $it rows" }.orEmpty()
        return "Result: ${table.rows.size} rows, ${table.columns.size} columns$cap"
    }

    /** The visible cell: single line, cut to [chars] (the column width) with an ellipsis. */
    fun displayCell(value: String?, chars: Int): String {
        val flat = value?.replace('\n', ' ')?.replace('\r', ' ') ?: "NULL"
        return if (flat.length <= chars) flat else flat.take(chars - 1) + "…"
    }

    private fun describeCell(value: String?): String {
        val flat = value?.replace('\n', ' ')?.replace('\r', ' ') ?: "empty"
        return if (flat.length <= DESCRIPTION_CELL_CHARS) flat else flat.take(DESCRIPTION_CELL_CHARS) + "…"
    }
}
