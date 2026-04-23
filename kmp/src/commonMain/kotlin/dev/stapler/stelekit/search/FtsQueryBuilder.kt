package dev.stapler.stelekit.search

/**
 * Builds safe FTS5 query strings from raw user input.
 *
 * Features:
 * - Balanced double-quote pairs are preserved as FTS5 phrase segments.
 * - Unbalanced quotes are stripped and the content treated as plain tokens.
 * - Multi-token queries use AND semantics — all terms must appear in a matching row.
 * - A `*` prefix-match wildcard is appended to every plain token, supporting
 *   search-as-you-type and partial word matching.
 * - Dangerous bare operators (AND/OR/NOT at the start) are stripped.
 * - Characters that would break FTS5 syntax (`:`, `^`, `~`, `{`, `}`, `[`, `]`, `!`)
 *   are removed from plain tokens.
 */
object FtsQueryBuilder {

    private val STRIP_CHARS = Regex("""[-:^~{}\[\]!]""")
    private val LEADING_OPERATORS = setOf("AND", "OR", "NOT")

    /**
     * Converts a raw user query into a safe FTS5 MATCH expression using AND semantics.
     * Every plain token receives a `*` prefix-match wildcard.
     *
     * Examples:
     *   "2025 Taxes"          → "2025* AND Taxes*"
     *   '"meeting notes"'     → '"meeting notes"'
     *   '"meeting notes" tax' → '"meeting notes" AND tax*'
     *   '"unclosed'           → 'unclosed*'
     *   'OR AND taxes'        → 'taxes*'
     *   ''                    → ''
     */
    fun build(rawQuery: String): String = buildQuery(rawQuery, " AND ")

    /**
     * Like [build] but joins tokens with OR semantics.
     * Use as a fallback when an AND query returns no results.
     *
     * Examples:
     *   "2025 Taxes" → "2025* OR Taxes*"
     */
    fun buildOr(rawQuery: String): String = buildQuery(rawQuery, " OR ")

    private fun buildQuery(rawQuery: String, joinStr: String): String {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return ""

        val segments = parseSegments(trimmed)
        if (segments.isEmpty()) return ""

        return segments.mapNotNull { seg ->
            when (seg) {
                is Segment.Phrase -> "\"${seg.content}\""
                is Segment.Token -> {
                    val clean = seg.content.replace(STRIP_CHARS, "").trim()
                    if (clean.isEmpty()) null else "$clean*"
                }
            }
        }.joinToString(joinStr)
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
