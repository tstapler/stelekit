// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
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
import dev.stapler.stelekit.ui.components.TableMessages
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
    val explicitAlign = remember(doc) { doc.displayData().explicitAlignments }
    val cols = state.columnCount
    val widths = List(cols) { state.view.widthOf(it + 1).dp }
    val total = widths.fold(GUTTER_WIDTH) { acc, w -> acc + w }
    var viewportPx by remember { mutableIntStateOf(0) }
    val freezeHeader = state.view.freezeHeaderRow
    val firstLazyRow = if (freezeHeader) 2 else 1
    val uriHandler = LocalUriHandler.current

    val gutterPx = with(density) { GUTTER_WIDTH.toPx() }
    val frozenColumn = if (state.view.freezeFirstColumn) 1 else 0
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val totalPx = with(density) { total.toPx() }
    SideEffect {
        state.rtl = rtl
        // The sticky gutter sits at the viewport's start edge: the left in LTR, the right (scrolling from the end) in RTL.
        hits.shield = if (rtl) {
            Rect(totalPx - hScroll.value - gutterPx, 0f, totalPx - hScroll.value, Float.MAX_VALUE)
        } else {
            Rect(hScroll.value.toFloat(), 0f, hScroll.value + gutterPx, Float.MAX_VALUE)
        }
        hits.frozenColumn = frozenColumn
        state.onRangeModeEntered = { runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) } }
    }
    LaunchedEffect(listState, hScroll) {
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, hScroll.value) }
            .collect { (i, o, h) ->
                state.recordScroll(i, o, h)
                state.pageRows = (listState.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)
            }
    }
    val revealTarget = if (state.rangeMode) state.selection.focus else state.selection.anchor
    LaunchedEffect(revealTarget, viewportPx) {
        revealCell(revealTarget, firstLazyRow, listState, hScroll, widths.map { with(density) { it.toPx() }.toInt() }, with(density) { GUTTER_WIDTH.toPx() }.toInt(), viewportPx)
    }

    Box(
        modifier.fillMaxSize().clipToBounds().onSizeChanged { viewportPx = it.width }.testTag("sheetGrid")
            .semantics {
                collectionInfo = SheetSemantics.gridInfo(state)
                if (state.readOnly) stateDescription = TableMessages.READ_ONLY_STATE
            },
    ) {
        Column(Modifier.horizontalScroll(hScroll).width(total).fillMaxHeight()) {
            LetterRow(state, widths, hScroll.value, colors)
            if (freezeHeader) {
                SheetRow(state, hits, 1, widths, alignments, explicitAlign, colors, hScroll.value)
            }
            Box(Modifier.width(total).weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
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
                        onRangeDrag = state::dragRange,
                        onShiftClick = state::shiftClick,
                        onLongPressCell = state::enterRangeMode,
                        onSecondaryClick = { state.openMenu(SheetMenuTarget.Cell(it)) },
                    ),
            ) {
                val count = (state.rowCount - firstLazyRow + 1).coerceAtLeast(0)
                items(count = count, key = { state.rowKeyOf(it + firstLazyRow) }) { index ->
                    SheetRow(state, hits, index + firstLazyRow, widths, alignments, explicitAlign, colors, hScroll.value)
                }
            }
            RangeHandles(state, hits)
            }
        }
    }
}

@Composable
private fun sheetColors() = sheetColorsFor(MaterialTheme.colorScheme, LocalTableVisualPrefs.current)

internal fun sheetColorsFor(scheme: androidx.compose.material3.ColorScheme, prefs: TableVisualPrefs): SheetColors {
    if (!prefs.highContrast) {
        return SheetColors(
            header = scheme.surfaceVariant,
            zebra = scheme.onSurface.copy(alpha = 0.06f),
            divider = scheme.outlineVariant,
            formulaTint = scheme.primary.copy(alpha = 0.08f),
            outline = sheetOutlineColor(scheme.primary, cellBackgrounds(scheme)),
            link = scheme.primary,
        )
    }
    // Forced colors: no tints, ink on surface only; state is carried by outline width and glyphs.
    val ink = highContrastInk(scheme.surface)
    return SheetColors(header = scheme.surface, zebra = Color.Transparent, divider = ink, formulaTint = Color.Transparent, outline = ink, link = ink)
}

@Composable
private fun LetterRow(state: SheetState, widths: List<androidx.compose.ui.unit.Dp>, hOffset: Int, colors: SheetColors) {
    Row(Modifier.height(LETTER_ROW_HEIGHT).background(colors.header).testTag("sheetLetters")) {
        Box(Modifier.width(GUTTER_WIDTH).fillMaxHeight().offset { IntOffset(hOffset, 0) }.zIndex(1f).background(colors.header))
        widths.forEachIndexed { i, w ->
            val col = i + 1
            val label = CellAddress.columnLabel(col)
            val indicator = state.view.sort?.takeIf { it.col == col }
            val frozen = col == 1 && state.view.freezeFirstColumn
            val pinned = if (frozen) Modifier.offset { IntOffset(hOffset, 0) }.zIndex(1f).background(colors.header) else Modifier
            Box(
                pinned.width(w).fillMaxHeight()
                    .clickable { state.selectColumn(col) }
                    .secondaryClick { state.selectColumn(col) }
                    .semantics {
                        heading()
                        contentDescription = TableMessages.columnHeaderLabel(
                            label,
                            indicator?.let { if (it.ascending) TableMessages.SORTED_ASCENDING else TableMessages.SORTED_DESCENDING },
                        )
                        customActions = SheetSemantics.headerActions(state, col)
                    }
                    .testTag("letter-$label"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label + (indicator?.let { if (it.ascending) " \u25B2" else " \u25BC" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
                ColumnResizeHandle(state, col, w, Modifier.align(Alignment.CenterEnd))
                SheetAnchoredMenu(state, SheetMenuTarget.Column(col))
            }
        }
    }
}

/** A mouse right-click on this node; touch and primary clicks pass untouched. */
private fun Modifier.secondaryClick(onClick: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.type == PointerType.Mouse && currentEvent.buttons.isSecondaryPressed) onClick()
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
    explicitAlign: List<TableColumnAlignment?>,
    colors: SheetColors,
    hOffset: Int,
) {
    Row(Modifier.height(IntrinsicSize.Min).heightIn(min = ROW_MIN_HEIGHT).fillMaxWidth().testTag("sheetRow-$row")) {
        Box(
            Modifier.width(GUTTER_WIDTH).fillMaxHeight().offset { IntOffset(hOffset, 0) }.zIndex(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { state.selectRow(row) }.secondaryClick { state.selectRow(row) }.testTag("gutter-$row"),
            contentAlignment = Alignment.Center,
        ) {
            Text("$row", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SheetAnchoredMenu(state, SheetMenuTarget.Row(row))
        }
        widths.forEachIndexed { i, w ->
            val align = cellTextAlign(alignments.getOrElse(i) { TableColumnAlignment.LEFT }, explicit = explicitAlign.getOrNull(i) != null)
            val pinned = if (i == 0 && state.view.freezeFirstColumn) {
                Modifier.offset { IntOffset(hOffset, 0) }.zIndex(1f).background(MaterialTheme.colorScheme.surface)
            } else {
                Modifier
            }
            SheetCell(state, hits, CellAddress(row, i + 1), w, align, colors, pinned)
        }
    }
}

/** An explicit GFM marker is physical (`:---` is left in any direction, UX-65); unmarked columns follow the layout direction. */
private fun cellTextAlign(effective: TableColumnAlignment, explicit: Boolean): TextAlign = when (effective) {
    TableColumnAlignment.LEFT -> if (explicit) TextAlign.Left else TextAlign.Start
    TableColumnAlignment.CENTER -> TextAlign.Center
    TableColumnAlignment.RIGHT -> if (explicit) TextAlign.Right else TextAlign.End
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
