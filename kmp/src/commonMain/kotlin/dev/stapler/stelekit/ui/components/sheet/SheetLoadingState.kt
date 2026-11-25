// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import dev.stapler.stelekit.ui.components.TableMessages

/** The thresholds of ux.md 3.8 in one place; the composables and the tests read the same numbers. */
internal object SheetLoadingState {
    const val SHEET_OPEN_INDICATOR_CELLS = 1_000
    const val SHEET_OPEN_INDICATOR_MS = 100L
    const val SAVING_INDICATOR_MS = 300L
    const val APPLYING_INDICATOR_MS = 300L

    /**
     * Cells in [content] without parsing it: a row of n cells has n+1 pipes, so pipes minus lines is a cheap
     * upper bound that is close enough to pick the off-thread path. The delimiter row counts as one row.
     */
    fun estimateCells(content: String): Int {
        var pipes = 0
        var lines = 1
        for (c in content) {
            if (c == '|') pipes++ else if (c == '\n') lines++
        }
        return (pipes - lines).coerceAtLeast(0)
    }

    /** Parse on the caller's thread below this, off it (and with the indicator) above. */
    fun parsesOffThread(content: String): Boolean = estimateCells(content) > SHEET_OPEN_INDICATOR_CELLS

    /** The indicator shows when the table is big, or the parse has already taken longer than 100 ms. */
    fun showOpeningIndicator(cells: Int, elapsedMs: Long): Boolean =
        cells > SHEET_OPEN_INDICATOR_CELLS || elapsedMs > SHEET_OPEN_INDICATOR_MS

    fun showSaving(pendingForMs: Long): Boolean = pendingForMs >= SAVING_INDICATOR_MS

    fun showApplying(runningForMs: Long): Boolean = runningForMs >= APPLYING_INDICATOR_MS

    /** What the status line says for the busy state, or null for none; saving wins over applying. */
    fun statusText(saving: Boolean, applying: Boolean): String? = when {
        saving -> TableMessages.SAVING
        applying -> TableMessages.APPLYING
        else -> null
    }
}
