package dev.stapler.stelekit.search

/**
 * Builds safe FTS5 query strings from raw user input.
 *
 * Features:
 * - Balanced double-quote pairs are preserved as FTS5 phrase segments.
 * - Unbalanced quotes are stripped and the content treated as plain tokens.
 * - Multi-token queries use OR semantics so partial matches surface at lower rank.
 * - A `*` prefix-match wildcard is appended to the last unquoted token, supporting
 *   search-as-you-type.
 * - Dangerous bare operators (AND/OR/NOT at the start) are stripped.
 * - Characters that would break FTS5 syntax (`:`, `^`, `~`, `{`, `}`, `[`, `]`, `!`)
 *   are removed from plain tokens.
 */
object FtsQueryBuilder {

    private val STRIP_CHARS = Regex("""[-:^~{}\[\]!]""")
    private val LEADING_OPERATORS = setOf("AND", "OR", "NOT")

    /**
     * Converts a raw user query into a safe FTS5 MATCH expression.
     *
     * Examples:
     *   "2025 Taxes"         → "2025 OR taxes*"
     *   '"meeting notes"'    → '"meeting notes"'
     *   '"meeting notes" tax' → '"meeting notes" OR tax*'
     *   '"unclosed'          → 'unclosed*'
     *   'OR AND taxes'       → 'taxes*'
     *   ''                   → ''
     */
    fun build(rawQuery: String): String {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return ""

        val segments = parseSegments(trimmed)
        if (segments.isEmpty()) return ""

        // Locate the last plain-token segment index for prefix wildcard
        val lastTokenIdx = segments.indexOfLast { it is Segment.Token }

        return segments.mapIndexed { i, seg ->
            when (seg) {
                is Segment.Phrase -> "\"${seg.content}\""
                is Segment.Token -> {
                    val clean = seg.content.replace(STRIP_CHARS, "").trim()
                    when {
                        clean.isEmpty() -> null
                        i == lastTokenIdx -> "$clean*"
                        else -> clean
                    }
                }
            }
        }
            .filterNotNull()
            .joinToString(" OR ")
    }

    // ── Internal parser ──────────────────────────────────────────────────────

    private sealed class Segment {
        /** A quoted phrase like `meeting notes`. */
        data class Phrase(val content: String) : Segment()
        /** A single unquoted token like `2025`. */
        data class Token(val content: String) : Segment()
    }

    private fun parseSegments(input: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        val buf = StringBuilder()
        var i = 0

        while (i < input.length) {
            if (input[i] == '"') {
                // Flush buffered plain text as tokens
                flushTokens(buf, segments)

                val closeIdx = input.indexOf('"', i + 1)
                if (closeIdx == -1) {
                    // Unbalanced quote: treat rest as plain tokens
                    buf.append(input.substring(i + 1))
                    i = input.length
                } else {
                    val phraseContent = input.substring(i + 1, closeIdx).trim()
                    if (phraseContent.isNotBlank()) {
                        segments.add(Segment.Phrase(phraseContent))
                    }
                    i = closeIdx + 1
                }
            } else {
                buf.append(input[i])
                i++
            }
        }

        flushTokens(buf, segments)

        // Drop leading operator tokens (AND / OR / NOT)
        return segments.dropWhile {
            it is Segment.Token && it.content.uppercase() in LEADING_OPERATORS
        }
    }

    private fun flushTokens(buf: StringBuilder, out: MutableList<Segment>) {
        val text = buf.toString().trim()
        if (text.isNotBlank()) {
            text.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .forEach { out.add(Segment.Token(it)) }
        }
        buf.clear()
    }
}
