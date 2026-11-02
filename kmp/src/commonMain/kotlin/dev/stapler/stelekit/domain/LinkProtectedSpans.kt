// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

/**
 * Character ranges of text where page-name auto-linking must never rewrite anything:
 * URLs (bare, `<autolink>`, markdown link targets), fenced and inline code, and
 * pre-existing `[[links]]` / `#tags`. Without this, "GitHub" inside `https://github.com/x`
 * was wrapped as `https://[[GitHub]].com/x`, breaking the link.
 *
 * Ranges are inclusive [IntRange]s over the original text.
 */
object LinkProtectedSpans {

    // Bare URL up to whitespace/angle/quote; trailing punctuation is trimmed afterwards.
    private val URL = Regex("""(?i)\b(?:https?|ftp|file|mailto):[^\s<>"]+|\bwww\.[^\s<>"]+""")
    private val FENCE = Regex("""(?s)```.*?(?:```|\z)""")
    private val INLINE_CODE = Regex("""`[^`\n]*`""")
    private val WIKI_LINK = Regex("""\[\[.*?]]""")
    private val HASH_TAG = Regex("""(?<![\w&])#(?:\[\[.*?]]|[^\s,.;:!?()\[\]{}"'#]+)""")
    private val TRAILING_PUNCT = setOf('.', ',', ';', ':', '!', '?', '\'', '"', '*', '_')

    fun find(text: String): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        FENCE.findAll(text).forEach { out.add(it.range) }
        INLINE_CODE.findAll(text).forEach { out.add(it.range) }
        WIKI_LINK.findAll(text).forEach { out.add(it.range) }
        HASH_TAG.findAll(text).forEach { out.add(it.range) }
        URL.findAll(text).forEach { m ->
            var end = m.range.last + 1
            // `[text](url)` and `(see url)`: drop a closing bracket the URL doesn't open itself.
            while (end > m.range.first &&
                (text[end - 1] in TRAILING_PUNCT || unbalancedCloser(text, m.range.first, end))
            ) end--
            if (end > m.range.first) out.add(m.range.first until end)
        }
        return out
    }

    private fun unbalancedCloser(text: String, start: Int, end: Int): Boolean {
        val c = text[end - 1]
        val opener = when (c) {
            ')' -> '('
            ']' -> '['
            '}' -> '{'
            else -> return false
        }
        var depth = 0
        for (i in start until end) {
            if (text[i] == opener) depth++ else if (text[i] == c) depth--
        }
        return depth < 0
    }

    /** True when `[start, end)` intersects any range in [spans]. */
    fun overlaps(spans: List<IntRange>, start: Int, end: Int): Boolean =
        spans.any { it.first < end && start <= it.last }

    /**
     * [AhoCorasickMatcher.findAll] with matches that touch a protected span removed.
     * Matching runs on the full text so word boundaries stay correct at span edges.
     */
    fun findOutsideProtected(matcher: AhoCorasickMatcher, text: String): List<AhoCorasickMatcher.MatchSpan> {
        val spans = find(text)
        val all = matcher.findAll(text)
        return if (spans.isEmpty()) all else all.filterNot { overlaps(spans, it.start, it.end) }
    }
}
