package dev.stapler.stelekit.domain

import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Maintains a reactive index of page names for the page-term suggestion feature.
 * Listens to [PageRepository.getAllPages] and rebuilds the [AhoCorasickMatcher]
 * on a background thread whenever the page set changes.
 *
 * Journal pages (date-named entries) are excluded by default to reduce noise from
 * date strings in block content inadvertently matching journal page names.
 *
 * Common English function words are excluded by default to avoid false-positive
 * wiki-link matches for pages with very generic names.
 *
 * Approximate matching: the index also registers stem variants (common English
 * suffixes) and parenthetical aliases so that e.g. "running" matches page "run"
 * and "Sam" matches page "Sam (Carlton)" when no exact "Sam" page exists.
 */
class PageNameIndex(
    private val pageRepository: PageRepository,
    private val scope: CoroutineScope,
    private val excludeJournalPages: Boolean = true,
    private val minNameLength: Int = MIN_NAME_LENGTH,
    private val stopwords: Set<String> = DEFAULT_STOPWORDS,
    private val rebuildDebounceMs: Long = REBUILD_DEBOUNCE_MS,
) {
    private val _entries = MutableStateFlow<List<AhoCorasickMatcher.TrieEntry>>(emptyList())

    /**
     * Pre-built matcher for the current page set. Rebuilt on a background thread
     * whenever the page list changes. Null until the first page list is received
     * or when the filtered page set is empty.
     */
    val matcher: StateFlow<AhoCorasickMatcher?> = _entries
        .map { entries -> if (entries.isEmpty()) null else AhoCorasickMatcher(entries) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            pageRepository.getAllPages()
                .distinctUntilChanged()
                // Coalesce rapid bursts (e.g. graph load saving pages one by one) into a
                // single rebuild. 500 ms matches the block-editor save debounce so a rename
                // and its save settle before the matcher is rebuilt.
                .debounce(rebuildDebounceMs)
                .collect { result ->
                    result.getOrNull()?.let { pages ->
                        _entries.value = buildEntries(pages.map { it.name to it.isJournal })
                    }
                }
        }
    }

    /**
     * Builds the flat list of [AhoCorasickMatcher.TrieEntry] for all eligible pages.
     *
     * Three-pass build with "first writer wins" priority:
     * 1. Exact canonical entries — highest priority.
     * 2. Parenthetical alias entries (base of "X (Y)" → page "X (Y)") — if pattern not yet seen.
     * 3. Stem variant entries — only if pattern not yet seen.
     */
    private fun buildEntries(pages: List<Pair<String, Boolean>>): List<AhoCorasickMatcher.TrieEntry> {
        // seenPatterns guards against duplicate/lower-priority registrations
        val seenPatterns = mutableSetOf<String>()
        val result = mutableListOf<AhoCorasickMatcher.TrieEntry>()

        // Eligible page names after applying filters
        val eligiblePages = pages.mapNotNull { (name, isJournal) ->
            if (name.length < minNameLength) return@mapNotNull null
            if (excludeJournalPages && isJournal) return@mapNotNull null
            val lower = name.lowercase()
            if (lower in stopwords) return@mapNotNull null
            lower to name
        }

        // Pass 1 — exact canonical
        for ((lower, canonical) in eligiblePages) {
            if (seenPatterns.add(lower)) {
                result += AhoCorasickMatcher.TrieEntry(lower, canonical)
            }
        }

        // Pass 2 — parenthetical aliases: "X (Y)" → alias "x" → canonical "X (Y)"
        for ((lower, canonical) in eligiblePages) {
            val baseAlias = extractParentheticalBase(lower) ?: continue
            if (baseAlias.length >= minNameLength && baseAlias !in stopwords && seenPatterns.add(baseAlias)) {
                result += AhoCorasickMatcher.TrieEntry(baseAlias, canonical)
            }
        }

        // Pass 3 — stem variants: single-word pages only (multi-word names produce nonsensical
        // variants like "meeting notess") pointing back to the canonical
        for ((_, canonical) in eligiblePages) {
            val base = canonical.lowercase()
            if (base.length < MIN_STEM_BASE_LENGTH || ' ' in base) continue
            for ((variant, baseLen) in stemVariants(base)) {
                if (seenPatterns.add(variant)) {
                    result += AhoCorasickMatcher.TrieEntry(variant, canonical, baseLen)
                }
            }
        }

        return result
    }

    companion object {
        const val MIN_NAME_LENGTH = 3
        const val MIN_STEM_BASE_LENGTH = 3
        const val REBUILD_DEBOUNCE_MS = 500L

        private val PARENTHETICAL_REGEX = Regex("""^(.+)\s+\([^)]+\)\s*$""")

        val DEFAULT_STOPWORDS: Set<String> = setOf(
            "the", "and", "for", "are", "but", "not", "you", "all", "can", "her",
            "was", "one", "our", "out", "day", "get", "has", "him", "his", "how",
            "man", "new", "now", "old", "see", "two", "way", "who", "boy", "did",
            "its", "let", "put", "say", "she", "too", "use",
        )

        /**
         * Extracts the base name from a parenthetical page title.
         * "sam (carlton)" → "sam", "alice (smith)" → "alice".
         * Returns null if the name does not follow the "X (Y)" pattern.
         */
        internal fun extractParentheticalBase(lowercaseName: String): String? =
            PARENTHETICAL_REGEX.find(lowercaseName)?.groupValues?.get(1)?.trim()

        /**
         * Generates inflected surface forms for [base] (already lowercase) via [EnglishInflector].
         * Returns pairs of (variant pattern, base length) so the TrieEntry can record
         * that the span covers only the base, not the appended suffix — e.g. "run" →
         * ("running", 3), enabling [[Run]]ning link insertion.
         */
        internal fun stemVariants(base: String): List<Pair<String, Int>> =
            EnglishInflector.inflect(base).map { it.text to it.baseLength }
    }
}
