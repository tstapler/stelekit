package dev.stapler.stelekit.tags

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TagSuggestionViewModel(
    private val engine: TagSuggestionEngine,
) {
    private val logger = Logger("TagSuggestionViewModel")
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { _, e ->
            if (e !is CancellationException) {
                logger.error("Uncaught error: ${e::class.simpleName}: ${e.message}")
                _state.value = TagSuggestionState.Error(e.message ?: "Unknown error")
            }
        }
    )

    private val _state = MutableStateFlow<TagSuggestionState>(TagSuggestionState.Idle)
    val state: StateFlow<TagSuggestionState> = _state.asStateFlow()

    private var suggestionJob: Job? = null

    /** Warm up the on-device model in the background. Call when a screen opens that may request suggestions. */
    fun preload() {
        scope.launch { engine.preload() }
    }

    fun requestSuggestions(blockUuid: String, blockContent: String, alreadyLinkedTerms: Set<String> = emptySet()) {
        suggestionJob?.cancel()

        suggestionJob = scope.launch {
            // GAP-003 fix (docs/journeys/insert-tag.md, Story D.1.1): this function previously set
            // state = Loading synchronously before dispatching this coroutine, forcing every
            // "Suggest tags" tap through a perceptible spinner frame even though Tier-1 local
            // matches (AhoCorasickMatcher exact hits) need no network round-trip — only the Tier-2
            // LLM call genuinely needs to wait. Emitting Ready directly, as soon as the (still
            // off-main-thread, for large-graph safety) local scan completes, removes that avoidable
            // step from the flagship button-path journey: chips now appear as soon as local matches
            // are known, with the LLM tier continuing to enrich the same Ready state exactly as
            // before via llmPending/llmSuggestions.
            val localSuggestions = engine.directMatch(blockContent)
            _state.value = TagSuggestionState.Ready(
                blockUuid = blockUuid,
                localSuggestions = localSuggestions,
                llmSuggestions = emptyList(),
                llmPending = engine.hasLlmProvider,
            )

            // Tier 2: LLM suggestions — async, may be empty if no provider
            engine.llmSuggest(blockContent, alreadyLinkedTerms).fold(
                ifLeft = { err ->
                    _state.update { current ->
                        if (current is TagSuggestionState.Ready && current.blockUuid == blockUuid) {
                            current.copy(llmError = err.message, llmPending = false)
                        } else current
                    }
                },
                ifRight = { llmSuggestions ->
                    _state.update { current ->
                        if (current is TagSuggestionState.Ready && current.blockUuid == blockUuid) {
                            current.copy(llmSuggestions = llmSuggestions, llmPending = false)
                        } else current
                    }
                }
            )
        }
    }

    fun dismiss() {
        suggestionJob?.cancel()
        _state.value = TagSuggestionState.Idle
    }

    fun close() {
        scope.cancel()
    }
}
