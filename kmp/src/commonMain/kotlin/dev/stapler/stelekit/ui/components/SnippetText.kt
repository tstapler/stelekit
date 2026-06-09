package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * Remaps highlight [ranges] from [stripped] coordinate space to [baseText] coordinate space.
 *
 * [parseMarkdownWithStyling] only removes characters (markdown markers) from [stripped] to
 * produce [baseText], so every character in [baseText] has a corresponding character in
 * [stripped]. We build a forward map `stripped[s] → baseText[b]` and use it to translate
 * each range. Ranges that collapse to empty after remapping are dropped.
 */
private fun remapRanges(stripped: String, baseText: String, ranges: List<IntRange>): List<IntRange> {
    val map = IntArray(stripped.length + 1) { baseText.length }
    var b = 0
    for (s in stripped.indices) {
        map[s] = b.coerceAtMost(baseText.length)
        if (b < baseText.length && stripped[s] == baseText[b]) b++
    }
    map[stripped.length] = baseText.length
    return ranges.mapNotNull { r ->
        val start = map[r.first.coerceAtMost(stripped.length)]
        val end = map[(r.last + 1).coerceAtMost(stripped.length)]
        if (start < end) start until end else null
    }
}

/**
 * Strips `<em>…</em>` tags from [raw], returning the plain text and the list of
 * highlight ranges (start..last inclusive) in the stripped string.
 */
internal fun parseEmTags(raw: String): Pair<String, List<IntRange>> {
    val sb = StringBuilder()
    val ranges = mutableListOf<IntRange>()
    var i = 0
    while (i < raw.length) {
        if (raw.startsWith("<em>", i)) {
            val start = sb.length
            i += "<em>".length
            while (i < raw.length && !raw.startsWith("</em>", i)) {
                sb.append(raw[i++])
            }
            ranges.add(start until sb.length)
            if (raw.startsWith("</em>", i)) i += "</em>".length
        } else {
            sb.append(raw[i++])
        }
    }
    return Pair(sb.toString(), ranges)
}

/**
 * Renders a search-result snippet string, converting `<em>…</em>` tags produced
 * by SQLite's FTS5 `highlight()` function into bold/highlighted spans, while also
 * rendering markdown bold/italic via [parseMarkdownWithStyling].
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
        val (strippedText, highlightRanges) = parseEmTags(snippet)
        val base = parseMarkdownWithStyling(strippedText, linkColor = Color.Unspecified, textColor = Color.Unspecified)
        val remapped = remapRanges(strippedText, base.text, highlightRanges)
        buildAnnotatedString {
            append(base)
            remapped.forEach { range ->
                addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = highlightColor), range.first, range.last + 1)
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
