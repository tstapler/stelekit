package dev.stapler.stelekit.domain

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Maintains a reactive index of page names for the page-term suggestion feature.
 * Listens to [PageRepository.getPageNameEntries] (a names-only projection — never
 * full Page objects) and rebuilds the [AhoCorasickMatcher] on a background thread
 * whenever the page set changes.
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

    private val logger = Logger("PageNameIndex")

    /**
     * Pre-built matcher for the current page set. Rebuilt on a background thread
     * whenever the page list changes. Null until the first page list is received
     * or when the filtered page set is empty.
     *
     * The trie build allocates one node per distinct pattern character (8 000+ page graphs
     * produce hundreds of thousands of nodes), so under memory pressure the construction
     * itself can throw OutOfMemoryError. Degrade to a null matcher (suggestions off) instead
     * of letting the Throwable escape the stateIn coroutine — uncaught, it kills the process
     * on Android.
     */
    /** Returns the canonical page names in the current matcher index snapshot. */
    fun vocabularyNames(): List<String> = _entries.value.map { it.canonical }.distinct()

    val matcher: StateFlow<AhoCorasickMatcher?> = _entries
        .map { entries ->
            if (entries.isEmpty()) null else try {
                AhoCorasickMatcher(entries)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error("Matcher build failed — suggestions disabled: ${e::class.simpleName}: ${e.message}")
                null
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            // Names-only projection: a standing observer must never materialize full Page
            // objects for the whole graph (properties maps, file paths, timestamps) — on
            // 8 000+ page graphs that re-allocation per write burst drove GC thrash and OOM
            // on Android. (name, isJournal) pairs are all the matcher needs.
            pageRepository.getPageNameEntries()
                .distinctUntilChanged()
                // Coalesce rapid bursts (e.g. graph load saving pages one by one) into a
                // single rebuild. 500 ms matches the block-editor save debounce so a rename
                // and its save settle before the matcher is rebuilt.
                .debounce(rebuildDebounceMs)
                // A Throwable from the upstream flow (e.g. OutOfMemoryError materializing a
                // large page list) must not escape this collector: uncaught coroutine
                // Throwables kill the process on Android. Keep the last good entries.
                .catch { e ->
                    logger.error("Page flow failed — keeping last matcher: ${e::class.simpleName}: ${e.message}")
                }
                .collect { result ->
                    result.getOrNull()?.let { pages ->
                        try {
                            _entries.value = buildEntries(pages.map { it.name to it.isJournal })
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logger.error("Entry rebuild failed — keeping last matcher: ${e::class.simpleName}: ${e.message}")
                        }
                    }
                }
        }
    }

    /**
     * Builds the flat list of [AhoCorasickMatcher.TrieEntry] for all eligible pages.
     *
     * Three-pass build with "first writer wins" priority:
     * 1. Exact canonical entries — highest priority.
     * 2. Parenthetical alias entries — both the base and the parenthesized content of
     *    "X (Y)" → page "X (Y)" (e.g. "Master Operating Procedure (MOP)" is reachable by
     *    typing either "Master Operating Procedure" or "MOP") — if pattern not yet seen.
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

        // Pass 2 — parenthetical aliases: "X (Y)" → alias "x" → canonical "X (Y)",
        // and "X (Y)" → alias "y" → canonical "X (Y)" (e.g. the acronym in parens)
        for ((lower, canonical) in eligiblePages) {
            addAliasIfEligible(extractParentheticalBase(lower), canonical, seenPatterns, result)
            addAliasIfEligible(extractParentheticalContent(lower), canonical, seenPatterns, result)
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

    /** Registers [alias] as a [TrieEntry] pointing to [canonical] if it passes eligibility checks. */
    private fun addAliasIfEligible(
        alias: String?,
        canonical: String,
        seenPatterns: MutableSet<String>,
        result: MutableList<AhoCorasickMatcher.TrieEntry>,
    ) {
        if (alias == null) return
        if (alias.length >= minNameLength && alias !in stopwords && seenPatterns.add(alias)) {
            result += AhoCorasickMatcher.TrieEntry(alias, canonical)
        }
    }

    companion object {
        const val MIN_NAME_LENGTH = 3
        const val MIN_STEM_BASE_LENGTH = 3
        const val REBUILD_DEBOUNCE_MS = 500L

        private val PARENTHETICAL_REGEX = Regex("""^(.+)\s+\(([^)]+)\)\s*$""")

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
         * Extracts the parenthesized content from a parenthetical page title — often an
         * acronym or disambiguator. "master operating procedure (mop)" → "mop",
         * "sam (carlton)" → "carlton". Returns null if the name does not follow "X (Y)".
         */
        internal fun extractParentheticalContent(lowercaseName: String): String? =
            PARENTHETICAL_REGEX.find(lowercaseName)?.groupValues?.get(2)?.trim()

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
