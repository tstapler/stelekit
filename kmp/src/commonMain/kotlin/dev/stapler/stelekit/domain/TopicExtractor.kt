// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import kotlin.math.ln
import kotlin.math.min

/**
 * Heuristic extractor for topic suggestions from raw text.
 *
 * Extracts three kinds of candidates:
 *  1. Multi-word capitalized noun phrases (2–4 consecutive capitalized tokens)
 *  2. CamelCase / mixed-case identifiers (e.g. TensorFlow, SQLDelight, GraphQL)
 *  3. Single plain-capitalized words — only when they appear in a non-sentence-start position
 *
 * Scores each candidate with `ln(1 + frequency) × capitalizationBonus × lengthBonus`,
 * normalises to 0–1, filters below 0.2 confidence, and returns at most 15 results.
 */
object TopicExtractor {

    /**
     * Broad structural stop list. Deliberately wider than [PageNameIndex.DEFAULT_STOPWORDS]
     * (which is optimised for link matching, not extraction).
     */
    val EXTRACTION_STOPWORDS: Set<String> = setOf(
        // academic / blog section headers
        "introduction", "conclusion", "abstract", "methodology",
        "acknowledgements", "references", "appendix",
        // pervasive tech acronyms
        "api", "url", "http", "https", "json", "rest",
        "html", "css", "sdk", "cli", "gui", "ide",
        // high-frequency generic nouns
        "data", "model", "system", "method", "result", "function",
        "approach", "paper", "work", "section", "figure", "table",
    )

    // 2–4 consecutive capitalized tokens
    private val MULTI_WORD_REGEX = Regex("""[A-Z][a-zA-Z]*(?:\s+[A-Z][a-zA-Z]*){1,3}""")

    // Words that contain at least one internal uppercase letter (TensorFlow, SQLDelight, GraphQL)
    private val CAMEL_CASE_REGEX = Regex("""\b[A-Z][a-zA-Z]*[A-Z][a-zA-Z]*\b""")

    // Single plain-capitalized words (3+ letters, Title-case): requires sentence-position check
    private val SINGLE_CAP_REGEX = Regex("""\b[A-Z][a-z]{2,}\b""")

    private data class CandidateData(
        var count: Int = 0,
        var isCamelCase: Boolean = false,
        var wordCount: Int = 1,
        // True when at least one occurrence is NOT at a sentence boundary
        var appearsNonSentenceStart: Boolean = false,
    )

    /**
     * Extract topic suggestions from [rawText], excluding any term whose
     * lowercase form already appears in [existingNames].
     */
    fun extract(rawText: String, existingNames: Set<String> = emptySet()): List<TopicSuggestion> {
        val existingLower = existingNames.map { it.lowercase() }.toSet()
        val candidates = mutableMapOf<String, CandidateData>()

        // Track ranges consumed by multi-word matches to avoid double-counting their words
        val multiWordRanges = mutableListOf<IntRange>()

        // 1. Multi-word capitalized phrases (2–4 words)
        MULTI_WORD_REGEX.findAll(rawText).forEach { match ->
            val term = match.value
            val words = term.split(Regex("""\s+"""))
            if (words.size in 2..4 && !isStopword(term)) {
                multiWordRanges.add(match.range)
                val data = candidates.getOrPut(term) { CandidateData(wordCount = words.size) }
                data.count++
                data.wordCount = words.size
                // Multi-word phrases don't need sentence-start gating
                data.appearsNonSentenceStart = true
            }
        }

        // 2. CamelCase / mixed-case identifiers — always included, no sentence-start check
        CAMEL_CASE_REGEX.findAll(rawText).forEach { match ->
            val term = match.value
            if (!isStopword(term)) {
                val data = candidates.getOrPut(term) { CandidateData(isCamelCase = true) }
                data.count++
                data.isCamelCase = true
                data.appearsNonSentenceStart = true
            }
        }

        // 3. Single plain-capitalized words — require at least one mid-sentence occurrence
        SINGLE_CAP_REGEX.findAll(rawText).forEach { match ->
            val term = match.value
            val start = match.range.first
            // Skip words that are part of a matched multi-word phrase
            if (multiWordRanges.any { start in it }) return@forEach
            if (isStopword(term)) return@forEach

            val atSentenceStart = isSentenceStart(rawText, start)
            val data = candidates.getOrPut(term) { CandidateData() }
            data.count++
            if (!atSentenceStart) {
                data.appearsNonSentenceStart = true
            }
        }

        // Filter: stopwords already removed above; remove existing names and sentence-start-only terms
        val filtered = candidates.entries.filter { (term, data) ->
            term.lowercase() !in existingLower && data.appearsNonSentenceStart
        }

        if (filtered.isEmpty()) return emptyList()

        // Score each candidate
        val scored = filtered.map { (term, data) ->
            val capitalizationBonus = if (data.isCamelCase || isAllCaps(term)) 1.5f else 1.0f
            val lengthBonus = min(1.0f, data.wordCount / 3.0f)
            val score = ln(1.0 + data.count) * capitalizationBonus * lengthBonus
            term to score.toFloat()
        }

        val maxScore = scored.maxOf { it.second }
        if (maxScore <= 0f) return emptyList()

        return scored
            .map { (term, score) ->
                TopicSuggestion(
                    term = term,
                    confidence = (score / maxScore).coerceIn(0f, 1f),
                    source = TopicSuggestion.Source.LOCAL,
                )
            }
            .filter { it.confidence >= 0.2f }
            .sortedByDescending { it.confidence }
            .take(15)
    }

    /** Returns true when [pos] is at the beginning of a sentence in [text]. */
    private fun isSentenceStart(text: String, pos: Int): Boolean {
        if (pos == 0) return true
        val before = text.substring(0, pos).trimEnd()
        if (before.isEmpty()) return true
        return before.last() in ".!?\n"
    }

    /**
     * Returns true when [term] or all its words are in [EXTRACTION_STOPWORDS].
     */
    private fun isStopword(term: String): Boolean {
        val lower = term.lowercase()
        if (lower in EXTRACTION_STOPWORDS) return true
        val words = lower.split(Regex("""\s+"""))
        return words.size > 1 && words.all { it in EXTRACTION_STOPWORDS }
    }

    /** Returns true when every letter in [term] is uppercase (e.g. "API"). */
    private fun isAllCaps(term: String): Boolean {
        val letters = term.filter { it.isLetter() }
        return letters.isNotEmpty() && letters.all { it.isUpperCase() }
    }
}
