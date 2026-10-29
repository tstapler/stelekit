// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.console.ResultGridLayout
import dev.stapler.stelekit.console.ResultTable
import dev.stapler.stelekit.ui.components.typedItemsIndexed

private val CHAR_WIDTH = 8.dp
private val CELL_PADDING = 8.dp
private val ROW_HEIGHT = 36.dp
private val MIN_TOUCH_TARGET = 48.dp
private val GRID_MAX_HEIGHT = 320.dp

/**
 * One `sql` result: a header row and a lazily composed body that scroll horizontally together while
 * the first column stays put, then the row-cap footer and the Export CSV / EXPLAIN chips.
 * Every row is a single TalkBack node whose description reads the row and its values.
 */
@Composable
internal fun ConsoleResultGrid(
    table: ResultTable,
    onExportCsv: (ResultTable) -> Unit,
    onExplain: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val widths = ResultGridLayout.columnChars(table)
    val hScroll = rememberScrollState()
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("console-result-grid")) {
        GridHeader(table, widths, hScroll)
        LazyColumn(
            modifier = Modifier.heightIn(max = GRID_MAX_HEIGHT).semantics { collectionInfo = CollectionInfo(table.rows.size, table.columns.size) },
        ) {
            typedItemsIndexed(table.rows, key = { index, _ -> index.toString() }) { index, row ->
                GridRow(table, index, row, widths, hScroll)
            }
        }
        GridActions(table, onExportCsv, onExplain)
    }
}

@Composable
private fun GridHeader(table: ResultTable, widths: List<Int>, hScroll: ScrollState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clearAndSetSemantics {
                contentDescription = ResultGridLayout.headerDescription(table)
                heading()
            },
    ) {
        GridCells(table.columns, widths, 0 until 1, bold = true)
        Row(modifier = Modifier.weight(1f).horizontalScroll(hScroll)) { GridCells(table.columns, widths, 1 until widths.size, bold = true) }
    }
}

@Composable
private fun GridRow(table: ResultTable, index: Int, row: List<String?>, widths: List<Int>, hScroll: ScrollState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = ResultGridLayout.rowDescription(table, index)
                collectionItemInfo = CollectionItemInfo(index, 1, 0, table.columns.size)
            },
    ) {
        GridCells(row, widths, 0 until 1, bold = false)
        Row(modifier = Modifier.weight(1f).horizontalScroll(hScroll)) { GridCells(row, widths, 1 until widths.size, bold = false) }
    }
}

@Composable
private fun GridCells(cells: List<String?>, widths: List<Int>, range: IntRange, bold: Boolean) {
    Row {
        for (i in range) {
            if (i >= widths.size) break
            GridCell(cells.getOrNull(i), widths[i], bold)
        }
    }
}

@Composable
private fun GridCell(value: String?, chars: Int, bold: Boolean) {
    val isNull = value == null && !bold
    Text(
        text = ResultGridLayout.displayCell(value, chars),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = if (bold) FontWeight.Bold else null,
        maxLines = 1,
        color = if (isNull) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .width(CHAR_WIDTH * chars + CELL_PADDING * 2)
            .defaultMinSize(minHeight = ROW_HEIGHT)
            .padding(horizontal = CELL_PADDING, vertical = 8.dp),
    )
}

@Composable
private fun GridActions(table: ResultTable, onExportCsv: (ResultTable) -> Unit, onExplain: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        table.truncatedAtRows?.let { cap ->
            Text("Showing first $cap rows", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("console-row-cap-footer"))
        }
        AssistChip(
            onClick = { onExportCsv(table) },
            label = { Text("Export CSV") },
            modifier = Modifier.defaultMinSize(minHeight = MIN_TOUCH_TARGET).testTag("console-export-csv"),
        )
        table.explainSql?.let { statement ->
            AssistChip(
                onClick = { onExplain(statement) },
                label = { Text("EXPLAIN QUERY PLAN") },
                modifier = Modifier.defaultMinSize(minHeight = MIN_TOUCH_TARGET).testTag("console-explain-chip"),
            )
        }
    }
}
