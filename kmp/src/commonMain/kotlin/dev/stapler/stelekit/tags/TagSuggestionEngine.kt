package dev.stapler.stelekit.tags

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.domain.PageNameIndex
import dev.stapler.stelekit.error.DomainError

class TagSuggestionEngine(
    private val pageNameIndex: PageNameIndex,
    private val llmTagProvider: LlmTagProvider? = null,
    /**
     * Lambda that returns the current vocabulary for LLM pre-filtering.
     * Injected rather than reading PageNameIndex._entries directly (which is private).
     * In App.kt, wire as: vocabularyProvider = { pageNameIndex.vocabularyNames() }
     */
    private val vocabularyProvider: () -> List<String> = { pageNameIndex.vocabularyNames() },
) {
    companion object {
        /**
         * AUTO_APPLY_THRESHOLD governs ONLY LLM suggestions (which range 0.50–0.85 by positional decay).
         * AhoCorasick local exact hits always return confidence=1.0 and are always autoApplied=true —
         * they are never gated by this threshold. Do not add a threshold check to directMatch().
         */
        const val AUTO_APPLY_THRESHOLD = 0.95f
    }

    /** True when an LLM provider is configured; false when suggestions are local-only. */
    val hasLlmProvider: Boolean get() = llmTagProvider != null

    /**
     * Synchronous local scan. Uses the current AhoCorasickMatcher snapshot (null = no suggestions).
     * All results are confidence=1.0 and autoApplied=true — exact page name matches require no threshold.
     * Run this off the main thread (withContext(Dispatchers.Default)) for large graphs.
     */
    fun directMatch(blockContent: String): List<TagSuggestion> {
        val matcher = pageNameIndex.matcher.value ?: return emptyList()
        // AhoCorasickMatcher.findAll() is the correct method name
        return matcher.findAll(blockContent.lowercase())
            .map { span ->
                TagSuggestion(
                    term = span.canonicalName,
                    confidence = 1.0f,
                    source = TagSuggestion.Source.LOCAL,
                    autoApplied = true,  // exact hits always auto-apply
                )
            }
            .distinctByTerm()
    }

    /**
     * Async LLM scan. Returns empty list when no LLM provider is configured.
     * vocabularyProvider() supplies the constrained vocabulary to the provider.
     */
    suspend fun llmSuggest(
        blockContent: String,
        alreadyLinkedTerms: Set<String> = emptySet(),
    ): Either<DomainError, List<TagSuggestion>> {
        val provider = llmTagProvider ?: return emptyList<TagSuggestion>().right()
        val alreadyLinkedLower = alreadyLinkedTerms.map(String::lowercase).toSet()
        val vocabulary = vocabularyProvider()
            .filter { it.lowercase() !in alreadyLinkedLower }
        val request = TagSuggestionRequest(
            blockUuid = "",
            blockContent = blockContent,
            pageVocabulary = vocabulary,
        )
        return provider.suggestTags(request).map { suggestions ->
            suggestions
                .filter { it.term.lowercase() !in alreadyLinkedLower }
                .distinctByTerm()
        }
    }

    private fun List<TagSuggestion>.distinctByTerm(): List<TagSuggestion> {
        val seen = mutableSetOf<String>()
        return filter { seen.add(it.term.lowercase()) }
    }
}
