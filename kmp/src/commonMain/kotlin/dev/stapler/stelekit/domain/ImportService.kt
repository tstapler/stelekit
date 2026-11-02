// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

/**
 * Result of scanning raw text for known page-name matches.
 *
 * @param linkedText  The original text with matched page names wrapped in wiki-link syntax
 *                    (`[[Page Name]]`). Names that occur only inside a URL are not rewritten
 *                    there; they are appended as a `#Tag` at the end of the text instead.
 * @param matchedPageNames  Distinct canonical page names the result now references (linked or
 *                          tagged), in order of first appearance.
 * @param topicSuggestions  Local heuristic suggestions for new pages that could be created.
 */
data class ScanResult(
    val linkedText: String,
    val matchedPageNames: List<String>,
    val topicSuggestions: List<TopicSuggestion> = emptyList(),
)

/**
 * Pure-function service for the Import feature.
 *
 * Scans [rawText] using the pre-built [AhoCorasickMatcher] and returns a [ScanResult]
 * that contains:
 *  - the text with matched page names auto-linked as `[[Name]]` (never inside URLs, code,
 *    property lines, or existing links/tags — see [LinkProtectedSpans])
 *  - `#Tag`s appended for pages that match only inside a URL
 *  - the distinct list of matched canonical page names
 *  - local heuristic topic suggestions via [TopicExtractor]
 */
object ImportService {

    /**
     * Scan [rawText] for known page names and return a [ScanResult].
     *
     * [TopicExtractor] is run on the raw text (before wiki-link brackets are inserted)
     * to preserve clean capitalisation for extraction.
     *
     * @param rawText       The unmodified import text.
     * @param matcher       Pre-built page-name matcher.
     * @param existingNames Page names already in the graph; suggestions matching these are
     *                      suppressed. Defaults to empty for backwards compatibility.
     */
    fun scan(
        rawText: String,
        matcher: AhoCorasickMatcher,
        existingNames: Set<String> = emptySet(),
    ): ScanResult {
        val protectedSpans = LinkProtectedSpans.analyze(rawText)
        val all = matcher.findAll(rawText)
        val spans = all.filterNot { protectedSpans.overlapsAny(it.start, it.end) }

        val sb = StringBuilder()
        var cursor = 0
        val seen = LinkedHashSet<String>()
        for (span in spans) {
            sb.append(rawText, cursor, span.start)
            sb.append("[[").append(span.canonicalName).append("]]")
            seen.add(span.canonicalName)
            cursor = span.end
        }
        sb.append(rawText, cursor, rawText.length)

        val urlOnly = all.filter { protectedSpans.isUrlOnly(it.start, it.end) }.map { it.canonicalName }
        val tagCandidates = urlOnly.distinct().filter { name -> seen.none { it.equals(name, ignoreCase = true) } }
        val (linkedText, tagged) = appendUrlTags(sb.toString(), rawText, tagCandidates)

        return ScanResult(
            linkedText = linkedText,
            matchedPageNames = (seen + tagged).toList(),
            topicSuggestions = TopicExtractor.extract(rawText, existingNames),
        )
    }

    /**
     * Wraps each plain-text occurrence of a term in [terms] with `[[term]]` syntax.
     *
     * Occurrences inside a protected span (URL, code, property line, existing `[[link]]` or `#tag`;
     * see [LinkProtectedSpans]) are skipped. A term whose only occurrences are inside URLs is
     * associated with the block by appending `#term` (or `#[[Multi Word]]`) at the end instead.
     * Matching is case-insensitive; the display form from [terms] is used as the link target. All
     * term matches are collected against the original (unmodified) [text] first, sorted by start
     * offset (ties favor the longest term), then swept left-to-right accepting only matches whose
     * start is at or past the previous accepted match's end. This greedy leftmost-then-longest
     * sweep keeps the result order-independent and prevents a shorter term (e.g. "Network") from
     * matching inside a longer term's own candidate span (e.g. "Neural Network Architecture"),
     * which would otherwise produce corrupt nested brackets.
     */
    fun insertWikiLinks(text: String, terms: List<String>): String {
        if (terms.isEmpty()) return text

        data class Match(val start: Int, val end: Int, val term: String)

        val protectedSpans = LinkProtectedSpans.analyze(text)

        val allMatches = terms.filter { it.isNotEmpty() }
            .flatMap { term ->
                Regex("""\b${Regex.escape(term)}\b""", RegexOption.IGNORE_CASE)
                    .findAll(text)
                    .map { Match(it.range.first, it.range.last + 1, term) }
            }
        val candidates = allMatches
            .filterNot { protectedSpans.overlapsAny(it.start, it.end) }
            .sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))

        val accepted = mutableListOf<Match>()
        var lastEnd = -1
        for (m in candidates) {
            if (m.start >= lastEnd) {
                accepted.add(m)
                lastEnd = m.end
            }
        }

        val linkedTerms = accepted.map { it.term.lowercase() }.toSet()
        val tagCandidates = allMatches
            .filter { protectedSpans.isUrlOnly(it.start, it.end) && it.term.lowercase() !in linkedTerms }
            .sortedBy { it.start }
            .map { it.term }
            .distinctBy { it.lowercase() }

        val sb = StringBuilder()
        var cursor = 0
        for (m in accepted) {
            sb.append(text, cursor, m.start)
            sb.append("[[").append(m.term).append("]]")
            cursor = m.end
        }
        sb.append(text, cursor, text.length)
        return appendUrlTags(sb.toString(), text, tagCandidates).first
    }

    private val TAG_SAFE = Regex("""[\p{L}\p{N}_\-]+""")

    internal fun hashtagFor(name: String): String =
        if (TAG_SAFE.matches(name)) "#$name" else "#[[$name]]"

    /** True when [text] already references [name] as `[[name]]`, `[[name|alias]]`, `#name` or `#[[name]]`. */
    internal fun alreadyReferences(text: String, name: String): Boolean {
        val n = Regex.escape(name)
        return Regex("""\[\[$n(?:\|[^\]]*)?\]\]|(?<![\w&])#$n(?![\p{L}\p{N}_\-])|#\[\[$n\]\]""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    }

    /**
     * Appends `#tag`s for [names] to [text] after its last body line — before any trailing
     * `key:: value` property lines and trailing blank lines, which are preserved verbatim.
     * Names that [original] or [text] already reference are skipped (so applying twice is a no-op).
     * Returns the new text and the names actually tagged; text with no body line is unchanged.
     */
    private fun appendUrlTags(text: String, original: String, names: List<String>): Pair<String, List<String>> {
        val tagged = names.filter { !alreadyReferences(original, it) && !alreadyReferences(text, it) }
        if (tagged.isEmpty()) return text to emptyList()
        val insertAt = bodyEnd(text)
        if (insertAt < 0) return text to emptyList()
        val tags = tagged.joinToString(" ") { hashtagFor(it) }
        return (text.substring(0, insertAt) + " " + tags + text.substring(insertAt)) to tagged
    }

    /** End offset of the last line that is neither blank nor a property line; -1 when none. */
    private fun bodyEnd(text: String): Int {
        var end = text.length
        while (end > 0) {
            val lineStart = text.lastIndexOf('\n', end - 1) + 1
            val line = text.substring(lineStart, end)
            if (line.isNotBlank() && !LinkProtectedSpans.PROPERTY_LINE.matches(line)) {
                return lineStart + line.trimEnd().length
            }
            end = lineStart - 1
        }
        return -1
    }
}
