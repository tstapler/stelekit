package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal data class TableData(
    val headers: List<String>,
    val alignments: List<TableColumnAlignment>,
    val rows: List<List<String>>,
)

internal enum class TableColumnAlignment { LEFT, CENTER, RIGHT }

internal fun parseTableContent(raw: String): TableData {
    val lines = raw.lines().filter { it.isNotBlank() }
    if (lines.size < 2) return TableData(emptyList(), emptyList(), emptyList())

    fun splitRow(line: String): List<String> =
        line.trim().trimStart('|').trimEnd('|').split('|').map { it.trim() }

    val headers = splitRow(lines[0])
    val alignments = splitRow(lines[1]).map { cell ->
        when {
            cell.startsWith(':') && cell.endsWith(':') -> TableColumnAlignment.CENTER
            cell.endsWith(':') -> TableColumnAlignment.RIGHT
            else -> TableColumnAlignment.LEFT
        }
    }
    val rows = lines.drop(2).map { splitRow(it) }
    return TableData(headers, alignments, rows)
}

/**
 * Renders a GFM pipe table with header row, alignment, and alternating row shading.
 *
 * TODO(CMP-4279): horizontalScroll inside LazyColumn may conflict on iOS
 */
@Composable
internal fun TableBlock(
    content: String,
    onStartEditing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tableData = remember(content) { parseTableContent(content) }

    if (tableData.headers.isEmpty()) {
        // Fallback: show raw content as plain text
        WikiLinkText(
            text = content,
            textColor = MaterialTheme.colorScheme.onBackground,
            linkColor = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
        return
    }

    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)

    // TODO(CMP-4279): horizontalScroll inside LazyColumn may conflict on iOS
    Box(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onStartEditing() }
    ) {
        Column(modifier = Modifier.wrapContentWidth(unbounded = true)) {
            // Header row
            Row(modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .height(IntrinsicSize.Min)
            ) {
                tableData.headers.forEachIndexed { i, header ->
                    val alignment = tableData.alignments.getOrNull(i) ?: TableColumnAlignment.LEFT
                    if (i > 0) VerticalDivider(color = borderColor, modifier = Modifier.fillMaxHeight())
                    TableCell(text = header, alignment = alignment, isHeader = true)
                }
            }
            HorizontalDivider(color = borderColor)
            // Body rows
            tableData.rows.forEachIndexed { rowIdx, row ->
                val bgColor = if (rowIdx % 2 == 0) Color.Transparent
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                Row(modifier = Modifier
                    .background(bgColor)
                    .height(IntrinsicSize.Min)
                ) {
                    row.forEachIndexed { colIdx, cell ->
                        val alignment = tableData.alignments.getOrNull(colIdx) ?: TableColumnAlignment.LEFT
                        if (colIdx > 0) VerticalDivider(color = dividerColor, modifier = Modifier.fillMaxHeight())
                        TableCell(text = cell, alignment = alignment, isHeader = false)
                    }
                }
                if (rowIdx < tableData.rows.size - 1) {
                    HorizontalDivider(color = dividerColor)
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    alignment: TableColumnAlignment,
    isHeader: Boolean,
) {
    val textAlign = when (alignment) {
        TableColumnAlignment.LEFT -> TextAlign.Start
        TableColumnAlignment.CENTER -> TextAlign.Center
        TableColumnAlignment.RIGHT -> TextAlign.End
    }
    val contentAlignment = when (alignment) {
        TableColumnAlignment.LEFT -> Alignment.CenterStart
        TableColumnAlignment.CENTER -> Alignment.Center
        TableColumnAlignment.RIGHT -> Alignment.CenterEnd
    }
    val textColor = if (isHeader) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .widthIn(min = 80.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = contentAlignment,
    ) {
        Text(
            text = text,
            color = textColor,
            style = if (isHeader) {
                MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            textAlign = textAlign,
        )
    }
}
