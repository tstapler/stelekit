// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.SortIndicator
import dev.stapler.stelekit.table.TableDoc
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.table.TableViewState
import dev.stapler.stelekit.ui.components.TableMessages

/** The non-drag width choices (ux.md S8); a width never touches the file (ADR-007). */
internal enum class WidthPreset(val label: String, val dp: Float?) {
    Narrow(TableMessages.WIDTH_NARROW, 96f),
    Medium(TableMessages.WIDTH_MEDIUM, 160f),
    Wide(TableMessages.WIDTH_WIDE, 240f),
    AutoFit(TableMessages.WIDTH_AUTO_FIT, null),
}

/** Pure width rules and the view-state follow-up of a structural edit. */
internal object ColumnWidths {
    // Auto-fit counts characters, not glyph metrics: a stable estimate that needs no text measurer or composition.
    private const val CHAR_DP = 8f
    private const val PADDING_DP = 24f
    const val AUTO_MIN_DP = 96f
    const val AUTO_MAX_DP = 240f
    const val SCAN_ROWS = 2_000

    /** The width after a pointer moved [deltaPx] along the x axis; the handle sits on the column's end edge, so RTL grows leftwards. */
    fun dragWidth(startDp: Float, deltaPx: Float, density: Float, rtl: Boolean): Float =
        startDp + (if (rtl) -deltaPx else deltaPx) / density

    fun autoFitDp(longestLine: Int): Float = (longestLine * CHAR_DP + PADDING_DP).coerceIn(AUTO_MIN_DP, AUTO_MAX_DP)

    /** The longest single line of any cell in [col], over at most [SCAN_ROWS] rows. */
    fun longestLine(doc: TableDoc, col: Int, textAt: (CellAddress) -> String): Int {
        var longest = 0
        for (row in 1..minOf(doc.rowCount, SCAN_ROWS)) {
            for (line in textAt(CellAddress(row, col)).lineSequence()) longest = maxOf(longest, line.length)
        }
        return longest
    }

    fun widthFor(preset: WidthPreset, doc: TableDoc, col: Int, textAt: (CellAddress) -> String): Float =
        preset.dp ?: autoFitDp(longestLine(doc, col, textAt))

    /** Row-order edits clear the sort indicator; column edits move widths and the indicator with their columns (ADR-007). */
    fun afterEdit(view: TableViewState, edit: TableEdit, columnCount: Int): TableViewState = when (edit) {
        is TableEdit.InsertRows, is TableEdit.DeleteRows, is TableEdit.MoveRows, is TableEdit.PasteRange -> view.onRowOrderChanged()
        is TableEdit.InsertCols -> view.remapColumns { c -> if (c >= edit.before) c + edit.count else c }
        is TableEdit.DeleteCols -> view.remapColumns { c ->
            when {
                c < edit.at -> c
                c >= edit.at + edit.count -> c - edit.count
                else -> null
            }
        }
        is TableEdit.MoveCols -> {
            val order = movedOrder(maxOf(columnCount, edit.from + edit.count - 1, edit.before - 1), edit.from, edit.count, edit.before)
            view.remapColumns { c -> order.indexOf(c).takeIf { it >= 0 }?.plus(1) }
        }
        else -> view
    }

    /** Mirrors the engine's move: the new left-to-right order of the old column numbers (not importable from `table`). */
    internal fun movedOrder(size: Int, from: Int, count: Int, before: Int): List<Int> {
        val all = (1..size).toList()
        val block = all.subList(from - 1, from - 1 + count)
        val rest = all.filterIndexed { i, _ -> i !in (from - 1) until (from - 1 + count) }
        val at = if (before > from + count - 1) before - 1 - count else before - 1
        return rest.toMutableList().apply { addAll(at, block) }
    }

    private fun TableViewState.remapColumns(map: (Int) -> Int?): TableViewState {
        val widths = columnWidthsDp.entries.mapNotNull { (c, w) -> map(c)?.let { it to w } }.toMap()
        val indicator = sort?.let { s -> map(s.col)?.let { SortIndicator(it, s.ascending) } }
        return copy(columnWidthsDp = widths, sort = indicator)
    }
}

private val RESIZE_HIT = 48.dp

/**
 * The drag handle on a column letter's right edge: a 48dp hit area (narrower only where the column is), a thin bar
 * drawn. The drag reads the pointer in root coordinates because the handle itself moves as the column grows; the
 * non-drag path is the header menu's width presets. Writes only [SheetState.view] (no commit).
 */
@Composable
internal fun ColumnResizeHandle(state: SheetState, col: Int, columnWidth: Dp, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    var coords: LayoutCoordinates? by remember { mutableStateOf(null) }
    val label = CellAddress.columnLabel(col)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier = modifier
            .width(minOf(RESIZE_HIT, columnWidth / 2))
            .fillMaxHeight()
            .onGloballyPositioned { coords = it }
            .semantics { contentDescription = "${TableMessages.RESIZE_COLUMN} $label" }
            .testTag("resize-$label")
            .pointerInput(col) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startWidth = state.view.widthOf(col)
                    val startX = coords?.localToRoot(down.position)?.x ?: return@awaitEachGesture
                    val ok = drag(down.id) { change ->
                        val x = coords?.localToRoot(change.position)?.x ?: return@drag
                        state.resizeColumn(col, ColumnWidths.dragWidth(startWidth, x - startX, density.density, rtl))
                        change.consume()
                    }
                    if (ok) state.announce(TableMessages.columnWidth(label, state.view.widthOf(col).toInt()))
                }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(Modifier.width(2.dp).size(2.dp, 20.dp).background(MaterialTheme.colorScheme.outline))
    }
}
