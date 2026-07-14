// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

/**
 * Result of scanning raw text for known page-name matches.
 *
 * @param linkedText  The original text with all matched page names wrapped in wiki-link
 *                    syntax: `[[Page Name]]`. Unchanged segments are kept as-is.
 * @param matchedPageNames  Distinct canonical page names found in the text, in order of
 *                          first appearance.
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
 *  - the text with matched page names auto-linked as `[[Name]]`
 *  - the distinct list of matched canonical page names
 *  - local heuristic topic suggestions via [TopicExtractor]
 */
object ImportService {

    /**
     * Scan [rawText] for known page names and return a [ScanResult].
     *
     * All matches reported by [matcher] are wrapped with `[[…]]` syntax.
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
        val spans = matcher.findAll(rawText)

        val linkedText: String
        val matchedPageNames: List<String>

        if (spans.isEmpty()) {
            linkedText = rawText
            matchedPageNames = emptyList()
        } else {
            val sb = StringBuilder()
            var cursor = 0
            val seen = LinkedHashSet<String>()

            for (span in spans) {
                if (span.start > cursor) {
                    sb.append(rawText, cursor, span.start)
                }
                sb.append("[[")
                sb.append(span.canonicalName)
                sb.append("]]")
                seen.add(span.canonicalName)
                cursor = span.end
            }

            if (cursor < rawText.length) {
                sb.append(rawText, cursor, rawText.length)
            }

            linkedText = sb.toString()
            matchedPageNames = seen.toList()
        }

        val suggestions = TopicExtractor.extract(rawText, existingNames)

        return ScanResult(
            linkedText = linkedText,
            matchedPageNames = matchedPageNames,
            topicSuggestions = suggestions,
        )
    }

    /**
     * Wraps each plain-text occurrence of a term in [terms] with `[[term]]` syntax.
     *
     * Occurrences already inside `[[…]]` are skipped. Matching is case-insensitive; the
     * display form from [terms] is used as the link target. All term matches are
     * collected against the original (unmodified) [text] first, then rendered in a
     * single left-to-right pass — this keeps the result order-independent and prevents
     * a shorter term (e.g. "Network") from matching inside a longer term's already-
     * wrapped span (e.g. "[[Neural Network Architecture]]"), which would otherwise
     * produce corrupt nested brackets.
     */
    fun insertWikiLinks(text: String, terms: List<String>): String {
        if (terms.isEmpty()) return text

        data class Match(val start: Int, val end: Int, val term: String)

        val existingLinkSpans = Regex("""\[\[.*?\]\]""").findAll(text)
            .map { it.range }
            .toList()
        fun overlapsExistingLink(start: Int, end: Int) =
            existingLinkSpans.any { it.first < end && start <= it.last }

        // Collect every candidate match for every term against the original text, then
        // sweep left-to-right keeping the first non-overlapping match — ties at the same
        // start favor the longest term, mirroring the old longest-first intent.
        val candidates = terms.filter { it.isNotEmpty() }
            .flatMap { term ->
                Regex("""\b${Regex.escape(term)}\b""", RegexOption.IGNORE_CASE)
                    .findAll(text)
                    .map { Match(it.range.first, it.range.last + 1, term) }
            }
            .filterNot { overlapsExistingLink(it.start, it.end) }
            .sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))

        val accepted = mutableListOf<Match>()
        var lastEnd = -1
        for (m in candidates) {
            if (m.start >= lastEnd) {
                accepted.add(m)
                lastEnd = m.end
            }
        }
        if (accepted.isEmpty()) return text

        val sb = StringBuilder()
        var cursor = 0
        for (m in accepted) {
            sb.append(text, cursor, m.start)
            sb.append("[[").append(m.term).append("]]")
            cursor = m.end
        }
        sb.append(text, cursor, text.length)
        return sb.toString()
    }
}
