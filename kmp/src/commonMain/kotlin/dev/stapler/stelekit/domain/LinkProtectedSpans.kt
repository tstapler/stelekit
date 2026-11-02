// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

/**
 * Character ranges of text where page-name auto-linking must never rewrite anything:
 * URLs (bare, `<autolink>`, markdown link targets), fenced and inline code, `key:: value`
 * property lines, and pre-existing `[[links]]` / `#tags`. Without this, "GitHub" inside
 * `https://github.com/x` was wrapped as `https://[[GitHub]].com/x`, breaking the link.
 *
 * Ranges are inclusive [IntRange]s over the original text.
 */
object LinkProtectedSpans {

    /** URL spans plus every other protected span; [others] excludes spans wholly inside a URL. */
    class Spans(val urls: List<IntRange>, val others: List<IntRange>) {
        val all: List<IntRange> get() = urls + others
        fun overlapsAny(start: Int, end: Int): Boolean = overlaps(urls, start, end) || overlaps(others, start, end)

        /** Inside a URL and not also inside code, a property line or an existing link. */
        fun isUrlOnly(start: Int, end: Int): Boolean = overlaps(urls, start, end) && !overlaps(others, start, end)
    }

    // Bare URL up to whitespace/angle/quote; trailing punctuation is trimmed afterwards.
    private val URL = Regex("""\b(?:https?|ftp|file|mailto):[^\s<>"]+|\bwww\.[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private val FENCE = Regex("""```[\s\S]*?(?:```|$)""")
    private val INLINE_CODE = Regex("""`[^`\n]*`""")
    private val WIKI_LINK = Regex("""\[\[.*?\]\]""")
    private val HASH_TAG = Regex("""(?<![\w&])#(?:\[\[.*?\]\]|[^\s,.;:!?()\[\]\{\}"'#]+)""")
    // Logseq property: `key:: value` (a space or end of line after "::" — not `std::vector`).
    internal val PROPERTY_LINE = Regex("""^[ \t]*[A-Za-z0-9_.\-]+::(?:[ \t].*)?$""", RegexOption.MULTILINE)
    private val TRAILING_PUNCT = setOf('.', ',', ';', ':', '!', '?', '\'', '"', '*', '_')

    fun analyze(text: String): Spans {
        if (text.isEmpty()) return Spans(emptyList(), emptyList())
        val urls = urlSpans(text)
        val others = ArrayList<IntRange>()
        for (re in listOf(FENCE, INLINE_CODE, WIKI_LINK, HASH_TAG, PROPERTY_LINE)) {
            re.findAll(text).forEach { m ->
                if (urls.none { it.first <= m.range.first && m.range.last <= it.last }) others.add(m.range)
            }
        }
        return Spans(urls, others)
    }

    fun find(text: String): List<IntRange> = analyze(text).all

    internal fun urlSpans(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
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

    /** True when [pattern] matches somewhere in [text] outside every protected span. */
    fun containsOutsideProtected(pattern: Regex, text: String): Boolean {
        val spans = analyze(text)
        return pattern.findAll(text).any { !spans.overlapsAny(it.range.first, it.range.last + 1) }
    }

    /**
     * Like [containsOutsideProtected] but only URLs are excluded. "Unlinked reference" lists keep
     * counting `#tag` mentions and code as unlinked, but a name inside a URL is not a mention.
     */
    fun containsOutsideUrls(pattern: Regex, text: String): Boolean {
        val urls = urlSpans(text)
        return pattern.findAll(text).any { !overlaps(urls, it.range.first, it.range.last + 1) }
    }

    /** [AhoCorasickMatcher.findAll] minus matches inside URLs (see [containsOutsideUrls]). */
    fun findOutsideUrls(matcher: AhoCorasickMatcher, text: String): List<AhoCorasickMatcher.MatchSpan> {
        val urls = urlSpans(text)
        return matcher.findAll(text).filterNot { overlaps(urls, it.start, it.end) }
    }

    /** [text] with every protected character replaced by a space; offsets are preserved. */
    fun mask(text: String): String {
        val spans = find(text)
        if (spans.isEmpty()) return text
        val chars = text.toCharArray()
        for (s in spans) for (i in s) if (chars[i] != '\n') chars[i] = ' '
        return chars.concatToString()
    }

    /**
     * [AhoCorasickMatcher.findAll] with matches that touch a protected span removed.
     * Matching runs on the full text so word boundaries stay correct at span edges.
     */
    fun findOutsideProtected(matcher: AhoCorasickMatcher, text: String): List<AhoCorasickMatcher.MatchSpan> {
        val spans = analyze(text)
        val all = matcher.findAll(text)
        return if (spans.urls.isEmpty() && spans.others.isEmpty()) all else all.filterNot { spans.overlapsAny(it.start, it.end) }
    }

    /**
     * Matches that fall inside a URL (and nowhere else protected). The page is associated with
     * the block by an appended `#tag` rather than by rewriting the URL.
     */
    fun findUrlOnly(matcher: AhoCorasickMatcher, text: String): List<AhoCorasickMatcher.MatchSpan> {
        val spans = analyze(text)
        if (spans.urls.isEmpty()) return emptyList()
        return matcher.findAll(text).filter { spans.isUrlOnly(it.start, it.end) }
    }
}
