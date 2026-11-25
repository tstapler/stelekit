// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.ui.components.LocalTablePreferences
import dev.stapler.stelekit.ui.components.TableColumnAlignment
import dev.stapler.stelekit.ui.components.effectiveAlignments
import kotlinx.coroutines.CancellationException

private val GUTTER_WIDTH = 44.dp
private val ROW_MIN_HEIGHT = 48.dp
private val LETTER_ROW_HEIGHT = 36.dp

/**
 * The lazy grid (Story 5.2): column letters and the table's header row stay above a [LazyColumn] of body rows keyed
 * by `RowKey`, the row-number gutter stays at the left edge, and one horizontal scroll state moves them all
 * together. Rows are as tall as their tallest cell, never a fixed height, and nothing animates.
 */
@Composable
@Suppress("MustUseTypedLazyItems") // the key is a Long, a Bundle-safe primitive
internal fun SheetGrid(state: SheetState, modifier: Modifier = Modifier) {
    val doc = state.doc ?: return
    val hScroll = rememberScrollState(state.horizontalScroll)
    val listState = rememberLazyListState(state.firstVisibleIndex, state.firstVisibleOffset)
    val hits = remember { CellHitRegistry() }
    val colors = sheetColors()
    val density = LocalDensity.current
    val numericRight = LocalTablePreferences.current.prefs.numericRightAlign
    val alignments = remember(doc, numericRight) { doc.displayData().effectiveAlignments(numericRight) }
    val cols = state.columnCount
    val widths = List(cols) { state.view.widthOf(it + 1).dp }
    val total = widths.fold(GUTTER_WIDTH) { acc, w -> acc + w }
    var viewportPx by remember { mutableIntStateOf(0) }
    val freezeHeader = state.view.freezeHeaderRow
    val firstLazyRow = if (freezeHeader) 2 else 1
    val uriHandler = LocalUriHandler.current

    val gutterPx = with(density) { GUTTER_WIDTH.toPx() }
    SideEffect { hits.shield = Rect(hScroll.value.toFloat(), 0f, hScroll.value + gutterPx, Float.MAX_VALUE) }
    LaunchedEffect(listState, hScroll) {
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, hScroll.value) }
            .collect { (i, o, h) ->
                state.recordScroll(i, o, h)
                state.pageRows = (listState.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)
            }
    }
    LaunchedEffect(state.selection.anchor, viewportPx) {
        revealCell(state.selection.anchor, firstLazyRow, listState, hScroll, widths.map { with(density) { it.toPx() }.toInt() }, with(density) { GUTTER_WIDTH.toPx() }.toInt(), viewportPx)
    }

    Box(modifier.fillMaxSize().clipToBounds().onSizeChanged { viewportPx = it.width }.testTag("sheetGrid")) {
        Column(Modifier.horizontalScroll(hScroll).width(total).fillMaxHeight()) {
            LetterRow(state, widths, hScroll.value, colors)
            if (freezeHeader) {
                SheetRow(state, hits, 1, widths, alignments, colors, hScroll.value)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .width(total)
                    .weight(1f)
                    .tableGestureOwner(
                        hits = hits,
                        mode = GestureMode.Interactive,
                        editingCell = { state.editingAddress },
                        onEditCell = { cell, _ -> state.onCellTap(cell) },
                        onLink = { link ->
                            if (link is CellLink.Url && isSafeCellUrl(link.url)) {
                                try {
                                    uriHandler.openUri(link.url)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // A URL the platform cannot open is ignored, like BlockViewer.
                                }
                            }
                        },
                        onLongPressSelect = null,
                        onToggleSelect = {},
                    ),
            ) {
                val count = (state.rowCount - firstLazyRow + 1).coerceAtLeast(0)
                items(count = count, key = { state.rowKeyOf(it + firstLazyRow) }) { index ->
                    SheetRow(state, hits, index + firstLazyRow, widths, alignments, colors, hScroll.value)
                }
            }
        }
    }
}

@Composable
private fun sheetColors() = SheetColors(
    header = MaterialTheme.colorScheme.surfaceVariant,
    zebra = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
    divider = MaterialTheme.colorScheme.outlineVariant,
    formulaTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
    outline = outlineColor(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surface),
    link = MaterialTheme.colorScheme.primary,
)

@Composable
private fun LetterRow(state: SheetState, widths: List<androidx.compose.ui.unit.Dp>, hOffset: Int, colors: SheetColors) {
    Row(Modifier.height(LETTER_ROW_HEIGHT).background(colors.header).testTag("sheetLetters")) {
        Box(Modifier.width(GUTTER_WIDTH).fillMaxHeight().offset { IntOffset(hOffset, 0) }.zIndex(1f).background(colors.header))
        widths.forEachIndexed { i, w ->
            val col = i + 1
            Box(
                Modifier.width(w).fillMaxHeight().clickable { state.selectColumn(col) }.testTag("letter-${CellAddress.columnLabel(col)}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(CellAddress.columnLabel(col), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun SheetRow(
    state: SheetState,
    hits: CellHitRegistry,
    row: Int,
    widths: List<androidx.compose.ui.unit.Dp>,
    alignments: List<TableColumnAlignment>,
    colors: SheetColors,
    hOffset: Int,
) {
    Row(Modifier.height(IntrinsicSize.Min).heightIn(min = ROW_MIN_HEIGHT).fillMaxWidth().testTag("sheetRow-$row")) {
        Box(
            Modifier.width(GUTTER_WIDTH).fillMaxHeight().offset { IntOffset(hOffset, 0) }.zIndex(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { state.selectRow(row) }.testTag("gutter-$row"),
            contentAlignment = Alignment.Center,
        ) {
            Text("$row", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        widths.forEachIndexed { i, w ->
            val align = when (alignments.getOrElse(i) { TableColumnAlignment.LEFT }) {
                TableColumnAlignment.LEFT -> TextAlign.Start
                TableColumnAlignment.CENTER -> TextAlign.Center
                TableColumnAlignment.RIGHT -> TextAlign.End
            }
            SheetCell(state, hits, CellAddress(row, i + 1), w, align, colors)
        }
    }
}

/** Scrolls just enough that [address] is on screen: a cell already inside the viewport is left alone. */
private suspend fun revealCell(
    address: CellAddress,
    firstLazyRow: Int,
    list: LazyListState,
    h: androidx.compose.foundation.ScrollState,
    widthsPx: List<Int>,
    gutterPx: Int,
    viewportPx: Int,
) {
    if (address.row >= firstLazyRow) {
        val index = address.row - firstLazyRow
        val visible = list.layoutInfo.visibleItemsInfo
        val fully = visible.any { it.index == index && it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset }
        if (!fully) {
            val below = visible.isNotEmpty() && index > visible.first().index
            list.scrollToItem(if (below) (index - (visible.size - 2)).coerceAtLeast(0) else index)
        }
    }
    if (viewportPx > 0 && address.col in 1..widthsPx.size) {
        val left = (0 until address.col - 1).sumOf { widthsPx[it] }
        val right = left + widthsPx[address.col - 1]
        if (left < h.value) h.scrollTo(left)
        else if (right + gutterPx > h.value + viewportPx) h.scrollTo(right + gutterPx - viewportPx)
    }
}
