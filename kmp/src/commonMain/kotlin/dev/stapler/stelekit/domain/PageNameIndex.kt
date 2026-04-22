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
 */
class PageNameIndex(
    private val pageRepository: PageRepository,
    private val scope: CoroutineScope,
    private val excludeJournalPages: Boolean = true,
    private val minNameLength: Int = MIN_NAME_LENGTH,
    private val stopwords: Set<String> = DEFAULT_STOPWORDS,
    private val rebuildDebounceMs: Long = REBUILD_DEBOUNCE_MS,
) {
    private val _canonicalNames = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Pre-built matcher for the current page set. Rebuilt on a background thread
     * whenever the page list changes. Null until the first page list is received
     * or when the filtered page set is empty.
     */
    val matcher: StateFlow<AhoCorasickMatcher?> = _canonicalNames
        .map { names -> if (names.isEmpty()) null else AhoCorasickMatcher(names) }
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
                        _canonicalNames.value = pages
                            .mapNotNull { page ->
                                if (page.name.length < minNameLength) return@mapNotNull null
                                if (excludeJournalPages && page.isJournal) return@mapNotNull null
                                val lower = page.name.lowercase()
                                if (lower in stopwords) return@mapNotNull null
                                lower to page.name
                            }
                            .toMap()
                    }
                }
        }
    }

    companion object {
        const val MIN_NAME_LENGTH = 3
        const val REBUILD_DEBOUNCE_MS = 500L

        val DEFAULT_STOPWORDS: Set<String> = setOf(
            "the", "and", "for", "are", "but", "not", "you", "all", "can", "her",
            "was", "one", "our", "out", "day", "get", "has", "him", "his", "how",
            "man", "new", "now", "old", "see", "two", "way", "who", "boy", "did",
            "its", "let", "put", "say", "she", "too", "use",
        )
    }
}
