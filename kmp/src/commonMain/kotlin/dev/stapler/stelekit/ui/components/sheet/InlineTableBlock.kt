// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.ColumnAlign
import dev.stapler.stelekit.table.TableDoc
import dev.stapler.stelekit.table.TableStripPolicy
import dev.stapler.stelekit.table.formula.TableCalc
import dev.stapler.stelekit.ui.components.BREAKABLE_CELL
import dev.stapler.stelekit.ui.components.LocalTablePreferences
import dev.stapler.stelekit.ui.components.LocalTableSheetOwner
import dev.stapler.stelekit.ui.components.TableColumnAlignment
import dev.stapler.stelekit.ui.components.TableData
import dev.stapler.stelekit.ui.components.TableDensity
import dev.stapler.stelekit.ui.components.TableDisplayPrefs
import dev.stapler.stelekit.ui.components.TableGrid
import dev.stapler.stelekit.ui.components.TableMessages
import dev.stapler.stelekit.ui.components.UNBREAKABLE_CELL
import dev.stapler.stelekit.ui.components.WikiLinkText
import dev.stapler.stelekit.ui.components.captureMaxWidth
import dev.stapler.stelekit.ui.components.effectiveAlignments
import kotlinx.coroutines.CancellationException

/** Cells composed inline before the rest waits for the sheet; distinct from the 10 000 paste cap and 20 000 formula cap. */
internal const val INLINE_CELL_WINDOW = 5_000

private val sheetLogger = Logger("TableSheet")

/** Body rows plus header that fit [windowCells] (at least the header), never more than [rowCount]. */
internal fun inlineRowWindow(rowCount: Int, columnCount: Int, windowCells: Int): Int =
    (windowCells / columnCount.coerceAtLeast(1)).coerceAtLeast(1).coerceAtMost(rowCount)

/** Flag-on table: tap edits a cell in place. The flag-off path stays in `TableBlock`. */
@Composable
internal fun InlineTableBlock(
    content: String,
    onStartEditing: () -> Unit,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    isInSelectionMode: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongPressSelect: (() -> Unit)? = null,
    linkColor: Color = MaterialTheme.colorScheme.primary,
    onLinkClick: (String) -> Unit = {},
    prefs: TableDisplayPrefs = LocalTablePreferences.current.prefs,
    blockUuid: BlockUuid? = null,
    onOpenSheet: (() -> Unit)? = null,
    inBlockSelectionMode: Boolean = false,
    cellWindow: Int = INLINE_CELL_WINDOW,
) {
    val latestCommit by rememberUpdatedState(onCommit)
    val state = remember(blockUuid) { InlineTableState(content) { latestCommit(it) } }
    val frozen = blockUuid != null && LocalTableSheetOwner.current == blockUuid
    val selectionMode = isInSelectionMode || inBlockSelectionMode

    LaunchedEffect(content) { state.onContent(content) }
    LaunchedEffect(frozen) { if (frozen) state.freeze() else if (state.isFrozen) state.reload(content) }
    LaunchedEffect(selectionMode) { if (selectionMode) state.commitAndClear() }

    val failure = state.failure
    LaunchedEffect(blockUuid, failure) {
        if (failure != null) sheetLogger.warn("table not editable as a grid: $failure")
    }
    if (failure != null) {
        Column(modifier) {
            Text(TableMessages.cannotEditAsGrid(failure), color = MaterialTheme.colorScheme.onSurface)
            ActionButton(TableMessages.EDIT_AS_MARKDOWN, onStartEditing)
        }
        return
    }
    val doc = state.doc ?: return

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
    val calc = remember(doc) { TableCalc.orNull(doc) }
    val alignments = remember(doc, prefs.numericRightAlign) { doc.displayData().effectiveAlignments(prefs.numericRightAlign) }
    val hits = remember { CellHitRegistry() }
    val availableWidth = remember { mutableIntStateOf(0) }
    val shownRows = inlineRowWindow(doc.rowCount, doc.columnCount, cellWindow)
    val colors = TableColors(
        border = MaterialTheme.colorScheme.outlineVariant,
        divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        header = MaterialTheme.colorScheme.surfaceVariant,
        zebra = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
    )

    Column(modifier) {
        doc.prefix.filter { it.isNotBlank() }.forEach { PlainLine(it, onStartEditing) }
        Box(
            modifier = Modifier
                .captureMaxWidth(availableWidth)
                .border(1.dp, colors.border, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface)
                .tableGestureOwner(
                    hits = hits,
                    mode = when {
                        selectionMode -> GestureMode.BlockSelection
                        frozen -> GestureMode.ReadOnly
                        else -> GestureMode.Interactive
                    },
                    editingCell = { state.editingAddress },
                    onEditCell = { cell, type ->
                        state.startEdit(cell, TableStripPolicy.profileAtEntry(isTouchLikePointer(type), accessibilityServiceActive = false))
                    },
                    onLink = { link ->
                        when (link) {
                            is CellLink.Page -> onLinkClick(link.name)
                            is CellLink.Url -> onUrlClick(link.url)
                        }
                    },
                    onLongPressSelect = onLongPressSelect,
                    onToggleSelect = onToggleSelect,
                )
                .semantics { collectionInfo = CollectionInfo(rowCount = doc.rowCount, columnCount = doc.columnCount) },
        ) {
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                TableGrid(columnCount = doc.columnCount, availableWidth = { availableWidth.intValue }, overflow = prefs.overflow) {
                    for (row in 1..shownRows) {
                        for (col in 1..doc.columnCount) {
                            val address = CellAddress(row, col)
                            InlineCell(
                                state = state,
                                hits = hits,
                                address = address,
                                display = cellDisplay(doc, calc, address),
                                alignment = alignments[col - 1],
                                colors = colors,
                                lastColumn = col == doc.columnCount,
                                lastRow = row == doc.rowCount,
                                density = prefs.density,
                                linkColor = linkColor,
                            )
                        }
                    }
                }
            }
        }
        if (shownRows < doc.rowCount) {
            RowWindowNote(
                shown = shownRows,
                total = doc.rowCount,
                onMaximize = onOpenSheet.takeIf { prefs.sheetEnabled }?.let { open -> { state.commitDraft(); open() } },
                onEditAsMarkdown = { state.commitDraft(); onStartEditing() },
            )
        }
        doc.suffix.filter { it.isNotBlank() }.forEach { PlainLine(it, onStartEditing) }
    }
}

private class TableColors(val border: Color, val divider: Color, val header: Color, val zebra: Color)

@Composable
private fun PlainLine(text: String, onClick: () -> Unit) {
    WikiLinkText(
        text = text,
        textColor = MaterialTheme.colorScheme.onBackground,
        linkColor = MaterialTheme.colorScheme.primary,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
}

@Composable
private fun RowWindowNote(shown: Int, total: Int, onMaximize: (() -> Unit)?, onEditAsMarkdown: () -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(TableMessages.showingFirstRows(shown, total), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            if (onMaximize != null) ActionButton(TableMessages.MAXIMIZE, onMaximize)
            ActionButton(TableMessages.EDIT_AS_MARKDOWN, onEditAsMarkdown)
        }
    }
}

/** Display values for alignment and numeric-column detection; cell text is the unescaped doc text. */
private fun TableDoc.displayData(): TableData {
    val cols = columnCount
    fun rowTexts(row: Int) = List(cols) { c -> cell(CellAddress(row, c + 1))?.value ?: "" }
    val explicit = List(cols) { c ->
        when (delimiter.aligns.getOrNull(c)) {
            ColumnAlign.LEFT -> TableColumnAlignment.LEFT
            ColumnAlign.CENTER -> TableColumnAlignment.CENTER
            ColumnAlign.RIGHT -> TableColumnAlignment.RIGHT
            else -> null
        }
    }
    return TableData(
        headers = rowTexts(1),
        alignments = explicit.map { it ?: TableColumnAlignment.LEFT },
        rows = (2..rowCount).map(::rowTexts),
        explicitAlignments = explicit,
    )
}

@Composable
private fun InlineCell(
    state: InlineTableState,
    hits: CellHitRegistry,
    address: CellAddress,
    display: CellDisplay,
    alignment: TableColumnAlignment,
    colors: TableColors,
    lastColumn: Boolean,
    lastRow: Boolean,
    density: TableDensity,
    linkColor: Color,
) {
    val isHeader = address.row == 1
    val editing = state.editingAddress == address
    val selected = state.selection?.contains(address) == true
    val textAlign = when (alignment) {
        TableColumnAlignment.LEFT -> TextAlign.Start
        TableColumnAlignment.CENTER -> TextAlign.Center
        TableColumnAlignment.RIGHT -> TextAlign.End
    }
    val (horizontal, vertical) = when (density) {
        TableDensity.COMPACT -> 6.dp to 0.dp
        TableDensity.COMFORTABLE -> 10.dp to 4.dp
    }
    val background = when {
        isHeader -> colors.header
        address.row % 2 == 0 -> colors.zebra
        else -> Color.Transparent
    }
    val rightRule = if (!lastColumn) colors.divider else null
    val bottomRule = if (!lastRow) (if (isHeader) colors.border else colors.divider) else null
    DisposableEffect(address) { onDispose { hits.remove(address) } }
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
            .onGloballyPositioned { hits.setCell(address, it) }
            .padding(horizontal = horizontal, vertical = vertical)
            .layoutId(if (!editing && display.text.none { it.isWhitespace() }) UNBREAKABLE_CELL else BREAKABLE_CELL)
            .semantics(mergeDescendants = !editing) {
                collectionItemInfo = CollectionItemInfo(rowIndex = address.row - 1, rowSpan = 1, columnIndex = address.col - 1, columnSpan = 1)
                if (selected) this.selected = true
                when (display) {
                    is CellDisplay.Formula -> stateDescription = display.formula
                    is CellDisplay.Failed -> {
                        stateDescription = display.formula
                        error(display.detail)
                    }
                    is CellDisplay.InvalidFormula -> error(display.detail)
                    is CellDisplay.Plain -> Unit
                }
                if (!editing) onClick(label = "Edit cell") { state.startEdit(address); true }
            },
    ) {
        val textColor = if (isHeader) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        if (editing) {
            CellEditor(
                state = state,
                textAlign = textAlign,
                textColor = textColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
        } else {
            CellContent(display, address, hits, textColor, linkColor, textAlign, if (isHeader) FontWeight.Bold else null)
        }
    }
}

@Composable
private fun CellContent(
    display: CellDisplay,
    address: CellAddress,
    hits: CellHitRegistry,
    textColor: Color,
    linkColor: Color,
    textAlign: TextAlign,
    fontWeight: FontWeight?,
) {
    val marker = when (display) {
        is CellDisplay.Formula -> "fx"
        is CellDisplay.InvalidFormula -> "!"
        else -> null
    }
    val color = if (display is CellDisplay.Failed) MaterialTheme.colorScheme.error else textColor
    Box {
        // The owner decides every tap, so the text's own handlers are no-ops.
        WikiLinkText(
            text = display.text,
            textColor = color,
            linkColor = linkColor,
            textAlign = textAlign,
            fontWeight = fontWeight,
            onHitInfo = { text, layout, coords -> hits.setText(address, text, layout, coords) },
            modifier = Modifier.fillMaxWidth().padding(end = if (marker != null) 16.dp else 0.dp),
        )
        if (marker != null) {
            Text(
                text = marker,
                fontSize = 9.sp,
                color = if (display is CellDisplay.InvalidFormula) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}
