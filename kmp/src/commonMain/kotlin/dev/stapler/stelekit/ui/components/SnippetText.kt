package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle

/**
 * Renders a search-result snippet string, converting `<em>…</em>` tags produced
 * by SQLite's FTS5 `highlight()` function into bold/highlighted spans.
 *
 * Falls back to plain text rendering if [snippet] contains no tags.
 * Returns nothing when [snippet] is null or blank.
 */
@Composable
fun SnippetText(
    snippet: String?,
    modifier: Modifier = Modifier,
    maxLines: Int = 2
) {
    if (snippet.isNullOrBlank()) return

    val highlightColor = MaterialTheme.colorScheme.primary
    val annotated = remember(snippet, highlightColor) {
        buildAnnotatedString {
            var remaining = snippet!!
            while (remaining.isNotEmpty()) {
                val emStart = remaining.indexOf("<em>")
                if (emStart == -1) {
                    append(remaining)
                    break
                }
                // Plain text before <em>
                if (emStart > 0) {
                    append(remaining.substring(0, emStart))
                }
                val emEnd = remaining.indexOf("</em>", emStart + 4)
                if (emEnd == -1) {
                    // Malformed: no closing tag, render rest as plain
                    append(remaining.substring(emStart + 4))
                    break
                }
                val highlighted = remaining.substring(emStart + 4, emEnd)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = highlightColor)) {
                    append(highlighted)
                }
                remaining = remaining.substring(emEnd + 5)
            }
        }
    }

    Text(
        text = annotated,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}
