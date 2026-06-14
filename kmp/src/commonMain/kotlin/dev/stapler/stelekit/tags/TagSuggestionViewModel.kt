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

    fun requestSuggestions(blockUuid: String, blockContent: String, alreadyLinkedTerms: Set<String> = emptySet()) {
        suggestionJob?.cancel()
        _state.value = TagSuggestionState.Loading

        suggestionJob = scope.launch {
            // Tier 1: local scan — synchronous, always available
            val localSuggestions = engine.directMatch(blockContent)
            _state.value = TagSuggestionState.Ready(
                blockUuid = blockUuid,
                localSuggestions = localSuggestions,
                llmSuggestions = emptyList(),
            )

            // Tier 2: LLM suggestions — async, may be empty if no provider
            engine.llmSuggest(blockContent, alreadyLinkedTerms).fold(
                ifLeft = { err ->
                    _state.update { current ->
                        if (current is TagSuggestionState.Ready && current.blockUuid == blockUuid) {
                            current.copy(llmError = err.message)
                        } else current
                    }
                },
                ifRight = { llmSuggestions ->
                    _state.update { current ->
                        if (current is TagSuggestionState.Ready && current.blockUuid == blockUuid) {
                            current.copy(llmSuggestions = llmSuggestions)
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
