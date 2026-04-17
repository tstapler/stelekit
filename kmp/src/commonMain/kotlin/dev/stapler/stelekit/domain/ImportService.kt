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
     * Occurrences already inside `[[…]]` are skipped (detected via lookbehind/lookahead).
     * Matching is case-insensitive; the display form from [terms] is used as the link target.
     * Terms are processed longest-first to avoid partial matches inside longer phrases.
     */
    fun insertWikiLinks(text: String, terms: List<String>): String {
        if (terms.isEmpty()) return text

        var result = text
        // Longest-first prevents shorter terms clobbering longer phrase matches
        val sortedTerms = terms.sortedByDescending { it.length }

        for (term in sortedTerms) {
            val escapedTerm = Regex.escape(term)
            // Negative lookbehind: not preceded by [[
            // Negative lookahead: not followed by ]]
            val pattern = Regex(
                """(?<!\[\[)\b${escapedTerm}\b(?!\]\])""",
                RegexOption.IGNORE_CASE,
            )
            result = pattern.replace(result) { "[[${term}]]" }
        }

        return result
    }
}
