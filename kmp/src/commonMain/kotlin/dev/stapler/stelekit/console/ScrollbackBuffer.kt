// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ScrollbackKind { INPUT, OUTPUT, ERROR }

/** [id] increases within one buffer between clears, so it is a stable list key. */
data class ScrollbackLine(val id: Long, val kind: ScrollbackKind, val text: String)

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
) {
    private val state = MutableStateFlow<List<ScrollbackLine>>(emptyList())
    val lines: StateFlow<List<ScrollbackLine>> = state.asStateFlow()

    fun appendAll(entries: List<Pair<ScrollbackKind, String>>) {
        if (entries.isEmpty()) return
        state.update { current ->
            var id = (current.lastOrNull()?.id ?: -1L) + 1
            val added = entries.flatMap { (kind, text) -> text.split('\n').map { ScrollbackLine(id++, kind, cut(it)) } }
            (current + added).takeLast(maxLines)
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
    }
}
