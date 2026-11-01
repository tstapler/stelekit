// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ScrollbackKind { INPUT, OUTPUT, ERROR }

/** [id] increases within one buffer between clears, so it is a stable list key. */
data class ScrollbackLine(val id: Long, val kind: ScrollbackKind, val text: String, val table: ResultTable? = null)

/** One thing to append: a text line (split on newlines) or, when [table] is set, one grid result. */
class ScrollbackEntry(val kind: ScrollbackKind, val text: String, val table: ResultTable? = null)

/**
 * Bounded history of console output: at most [maxLines] lines (oldest dropped first), each cut to
 * [maxCellChars] so one huge value cannot pin memory. One [appendAll] call is one state emission,
 * which is how [ConsoleSession] batches output.
 *
 * Not built: spilling evicted lines to a file. Nothing in the first slice produces output large
 * enough to need it (`logs` is bounded), so eviction simply drops the oldest lines.
 */
class ScrollbackBuffer(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxCellChars: Int = DEFAULT_MAX_CELL_CHARS,
    private val maxTables: Int = DEFAULT_MAX_TABLES,
) {
    private val state = MutableStateFlow<List<ScrollbackLine>>(emptyList())
    val lines: StateFlow<List<ScrollbackLine>> = state.asStateFlow()

    fun appendAll(entries: List<Pair<ScrollbackKind, String>>) = appendEntries(entries.map { (kind, text) -> ScrollbackEntry(kind, text) })

    /**
     * Keeps at most [maxTables] grid results; an older one is replaced by a one-line note so retained
     * memory stays bounded (a table can hold 500 rows of 2 KB cells).
     */
    fun appendEntries(entries: List<ScrollbackEntry>) {
        if (entries.isEmpty()) return
        state.update { current ->
            var id = (current.lastOrNull()?.id ?: -1L) + 1
            val added = entries.flatMap { e ->
                if (e.table != null) listOf(ScrollbackLine(id++, e.kind, "", e.table))
                else e.text.split('\n').map { ScrollbackLine(id++, e.kind, cut(it)) }
            }
            evictOldTables((current + added).takeLast(maxLines))
        }
    }

    private fun evictOldTables(lines: List<ScrollbackLine>): List<ScrollbackLine> {
        var excess = lines.count { it.table != null } - maxTables
        if (excess <= 0) return lines
        return lines.map { line ->
            val table = line.table
            if (table != null && excess > 0) {
                excess--
                line.copy(text = "(earlier result: ${table.rows.size} rows x ${table.columns.size} columns, not kept)", table = null)
            } else {
                line
            }
        }
    }

    fun clear() {
        state.value = emptyList()
    }

    private fun cut(line: String): String =
        if (line.length <= maxCellChars) line else line.take(maxCellChars) + " ... [+${line.length - maxCellChars} chars]"

    companion object {
        const val DEFAULT_MAX_LINES = 5_000
        const val DEFAULT_MAX_CELL_CHARS = 2_048
        const val DEFAULT_MAX_TABLES = 3
    }
}
