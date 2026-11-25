package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.ui.components.sheet.InlineTableBlock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.unit.dp

/** Widest a column may grow when cells scroll instead of wrapping, so one long cell cannot dominate. */
internal const val UNBREAKABLE_CELL = "unbreakable"
internal const val BREAKABLE_CELL = "breakable"
internal val MAX_SCROLL_COLUMN_WIDTH = 480.dp

/**
 * Renders a GFM pipe table: one column grid shared by every row, cell text wrapped (or scrolled,
 * per [TableDisplayPrefs.overflow]) rather than clipped, GFM alignment markers honoured, and
 * cell text rendered through [WikiLinkText] like every other block.
 *
 * TODO(CMP-4279): horizontalScroll inside LazyColumn may conflict on iOS
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TableBlock(
    content: String,
    onStartEditing: () -> Unit,
    modifier: Modifier = Modifier,
    isInSelectionMode: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongPressSelect: (() -> Unit)? = null,
    linkColor: Color = MaterialTheme.colorScheme.primary,
    onLinkClick: (String) -> Unit = {},
    prefs: TableDisplayPrefs = LocalTablePreferences.current.prefs,
    // Inline-edit seam: read only when [TableDisplayPrefs.inlineEditEnabled] is on and [onCommit] is given.
    blockUuid: BlockUuid? = null,
    onCommit: ((String) -> Unit)? = null,
    onOpenSheet: (() -> Unit)? = null,
    inBlockSelectionMode: Boolean = false,
) {
    if (prefs.inlineEditEnabled && onCommit != null) {
        InlineTableBlock(
            content = content,
            onStartEditing = onStartEditing,
            modifier = modifier,
            isInSelectionMode = isInSelectionMode,
            onToggleSelect = onToggleSelect,
            onLongPressSelect = onLongPressSelect,
            linkColor = linkColor,
            onLinkClick = onLinkClick,
            prefs = prefs,
            blockUuid = blockUuid,
            onCommit = onCommit,
            onOpenSheet = onOpenSheet,
            inBlockSelectionMode = inBlockSelectionMode,
        )
    } else if (prefs.sheetEnabled && onOpenSheet != null) {
        // Flag matrix row "inline off, sheet on": the Maximize button is the only way into the sheet.
        Column(modifier) {
            FlagOffTableBlock(content, onStartEditing, isInSelectionMode, onToggleSelect, onLongPressSelect, linkColor, onLinkClick, prefs)
            TextButton(onClick = onOpenSheet, modifier = Modifier.heightIn(min = 48.dp)) { Text(TableMessages.MAXIMIZE) }
        }
    } else {
        FlagOffTableBlock(content, onStartEditing, isInSelectionMode, onToggleSelect, onLongPressSelect, linkColor, onLinkClick, prefs, modifier)
    }
}

/** Today's renderer, untouched: used whenever inline editing is off. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FlagOffTableBlock(
    content: String,
    onStartEditing: () -> Unit,
    isInSelectionMode: Boolean,
    onToggleSelect: () -> Unit,
    onLongPressSelect: (() -> Unit)?,
    linkColor: Color,
    onLinkClick: (String) -> Unit,
    prefs: TableDisplayPrefs,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val onUrlClick: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ignore a URL the platform cannot open, like BlockViewer.
        }
    }
    val tableData = remember(content) { parseTableContent(content) }

    if (tableData.headers.isEmpty()) {
        // Fallback: show raw content as plain text
        WikiLinkText(
            text = content,
            textColor = MaterialTheme.colorScheme.onBackground,
            linkColor = MaterialTheme.colorScheme.primary,
            onLinkClick = onLinkClick,
            onUrlClick = onUrlClick,
            onClick = onStartEditing,
            isInSelectionMode = isInSelectionMode,
            onToggleSelect = onToggleSelect,
            onLongPressSelect = onLongPressSelect,
            modifier = modifier,
        )
        return
    }

    val alignments = remember(tableData, prefs.numericRightAlign) { tableData.effectiveAlignments(prefs.numericRightAlign) }
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val headerBg = MaterialTheme.colorScheme.surfaceVariant
    val zebraBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val availableWidth = remember { mutableIntStateOf(0) }
    val lastRow = tableData.rows.size // header is row 0

    Box(
        modifier = modifier
            .captureMaxWidth(availableWidth)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onLongClick = onLongPressSelect,
                onClick = { if (isInSelectionMode) onToggleSelect() else onStartEditing() },
            )
    ) {
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            TableGrid(
                columnCount = tableData.columnCount,
                availableWidth = { availableWidth.intValue },
                overflow = prefs.overflow,
            ) {
                val allRows = listOf(tableData.headers) + tableData.rows
                allRows.forEachIndexed { rowIdx, row ->
                    row.forEachIndexed { colIdx, cell ->
                        TableCell(
                            text = cell,
                            alignment = alignments[colIdx],
                            isHeader = rowIdx == 0,
                            background = when {
                                rowIdx == 0 -> headerBg
                                rowIdx % 2 == 0 -> zebraBg
                                else -> Color.Transparent
                            },
                            rightRule = if (colIdx < tableData.columnCount - 1) dividerColor else null,
                            bottomRule = if (rowIdx < lastRow) (if (rowIdx == 0) borderColor else dividerColor) else null,
                            density = prefs.density,
                            linkColor = linkColor,
                            onLinkClick = onLinkClick,
                            onUrlClick = onUrlClick,
                            onStartEditing = onStartEditing,
                            isInSelectionMode = isInSelectionMode,
                            onToggleSelect = onToggleSelect,
                            onLongPressSelect = onLongPressSelect,
                        )
                    }
                }
            }
        }
    }
}

/** Publishes the width this block is offered, so the grid can fit columns to it while sitting inside a scroller. */
internal fun Modifier.captureMaxWidth(into: MutableIntState): Modifier = layout { measurable, constraints ->
    into.intValue = constraints.maxWidth
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * Lays [content] (rows * [columnCount] cells, row-major) on one column grid. Column widths come
 * from every cell's intrinsic widths via [computeColumnWidths]; each row is as tall as its
 * tallest cell at that width.
 */
@Composable
internal fun TableGrid(
    columnCount: Int,
    availableWidth: () -> Int,
    overflow: TableOverflow,
    content: @Composable () -> Unit,
) {
    Layout(content = content) { measurables, _ ->
        val cols = columnCount
        val rowCount = measurables.size / cols
        val avail = availableWidth()
        val mins = IntArray(cols)
        val softMins = IntArray(cols)
        val maxs = IntArray(cols)
        measurables.forEachIndexed { i, m ->
            val c = i % cols
            val soft = m.minIntrinsicWidth(Constraints.Infinity)
            val max = m.maxIntrinsicWidth(Constraints.Infinity)
            // A cell with no whitespace is one token: the line breaker would split "249.4" at the '.', so it keeps its natural width.
            mins[c] = maxOf(mins[c], if (m.layoutId == UNBREAKABLE_CELL) max else soft)
            softMins[c] = maxOf(softMins[c], soft)
            maxs[c] = maxOf(maxs[c], max)
        }
        // If those whole tokens cannot fit, let the widest break (a long URL) rather than force a scroll; SCROLL mode keeps them whole up to the cap.
        if (overflow == TableOverflow.WRAP && avail > 0) {
            while (mins.sum() > avail) {
                val widest = (0 until cols).filter { mins[it] > minOf(softMins[it], avail / cols) }.maxByOrNull { mins[it] - minOf(softMins[it], avail / cols) } ?: break
                // The line breaker never splits a URL, but Compose hard-breaks a word wider than its column, so an equal share is a safe floor.
                mins[widest] = minOf(softMins[widest], avail / cols)
            }
        }
        val widths = computeColumnWidths(
            minWidths = mins,
            maxWidths = maxs,
            available = avail,
            overflow = overflow,
            maxColumnWidth = MAX_SCROLL_COLUMN_WIDTH.roundToPx(),
        )
        val heights = IntArray(rowCount) { r ->
            (0 until cols).maxOf { c -> measurables[r * cols + c].minIntrinsicHeight(widths[c]) }
        }
        val placeables = measurables.mapIndexed { i, m ->
            m.measure(Constraints.fixed(widths[i % cols], heights[i / cols]))
        }
        layout(widths.sum(), heights.sum()) {
            var y = 0
            for (r in 0 until rowCount) {
                var x = 0
                for (c in 0 until cols) {
                    placeables[r * cols + c].placeRelative(x, y)
                    x += widths[c]
                }
                y += heights[r]
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    alignment: TableColumnAlignment,
    isHeader: Boolean,
    background: Color,
    rightRule: Color?,
    bottomRule: Color?,
    density: TableDensity,
    linkColor: Color,
    onLinkClick: (String) -> Unit,
    onUrlClick: (String) -> Unit,
    onStartEditing: () -> Unit,
    isInSelectionMode: Boolean,
    onToggleSelect: () -> Unit,
    onLongPressSelect: (() -> Unit)?,
) {
    val textAlign = when (alignment) {
        TableColumnAlignment.LEFT -> TextAlign.Start
        TableColumnAlignment.CENTER -> TextAlign.Center
        TableColumnAlignment.RIGHT -> TextAlign.End
    }
    val (horizontal, vertical) = when (density) {
        TableDensity.COMPACT -> 6.dp to 0.dp
        TableDensity.COMFORTABLE -> 10.dp to 4.dp
    }
    Box(
        modifier = Modifier
            .drawBehind {
                drawRect(background)
                if (rightRule != null) {
                    val x = if (layoutDirection == LayoutDirection.Rtl) 0.5f else size.width - 0.5f
                    drawLine(rightRule, Offset(x, 0f), Offset(x, size.height), 1f)
                }
                if (bottomRule != null) {
                    drawLine(bottomRule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
                }
            }
            .padding(horizontal = horizontal, vertical = vertical)
            .layoutId(if (text.none { it.isWhitespace() }) UNBREAKABLE_CELL else BREAKABLE_CELL),
    ) {
        WikiLinkText(
            text = text,
            textColor = if (isHeader) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            linkColor = linkColor,
            onLinkClick = onLinkClick,
            onUrlClick = onUrlClick,
            onClick = onStartEditing,
            isInSelectionMode = isInSelectionMode,
            onToggleSelect = onToggleSelect,
            onLongPressSelect = onLongPressSelect,
            textAlign = textAlign,
            fontWeight = if (isHeader) FontWeight.Bold else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
