package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private fun extractCodeBody(content: String): String {
    val lines = content.lines()
    val isFenceLine: (String) -> Boolean = { it.trim().let { t -> t.startsWith("```") || t.startsWith("~~~") } }
    val start = if (lines.firstOrNull()?.let(isFenceLine) == true) 1 else 0
    val end = if (lines.lastOrNull()?.let(isFenceLine) == true) lines.size - 1 else lines.size
    return if (start < end) lines.subList(start, end).joinToString("\n") else ""
}

/**
 * Renders a fenced code block with optional language label,
 * monospace font, and horizontal scrolling.
 * Tapping the block enters edit mode.
 */
@Composable
internal fun CodeFenceBlock(
    content: String,
    language: String,
    onStartEditing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val codeText = remember(content) { extractCodeBody(content) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onStartEditing() }
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            if (language.isNotBlank()) {
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Box(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    BasicText(
                        text = codeText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        softWrap = false,
                    )
                }
            }
        }
    }
}
