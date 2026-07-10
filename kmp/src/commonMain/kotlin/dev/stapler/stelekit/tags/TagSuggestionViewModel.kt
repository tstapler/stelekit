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
    // UUID of the block the current suggestionJob is running for (null = no job).
    private var activeBlockUuid: String? = null
    // Results cache keyed by block UUID. Survives dismiss() so the LLM can finish in the
    // background and the sheet shows instantly on reopen.
    private val cache = mutableMapOf<String, TagSuggestionState.Ready>()

    /** Warm up the on-device model. Called at app start so first real request is never cold. */
    fun preload() {
        scope.launch { engine.preload() }
    }

    fun requestSuggestions(blockUuid: String, blockContent: String, alreadyLinkedTerms: Set<String> = emptySet()) {
        val cached = cache[blockUuid]
        if (cached != null) {
            _state.value = cached
            // If LLM is already running in the background for this block, restore state and wait —
            // don't restart the job. The background job will update _state and cache when done.
            if (cached.llmPending && activeBlockUuid == blockUuid) return
            // Fully resolved — nothing more to do.
            if (!cached.llmPending) return
            // Pending but job was cancelled (user switched to another block) — fall through to re-run.
        }

        // Cancel the previous job only if it's for a different block.
        suggestionJob?.cancel()
        activeBlockUuid = blockUuid

        suggestionJob = scope.launch {
            // GAP-003 fix: emit local matches immediately so chips appear without waiting for LLM.
            val localSuggestions = engine.directMatch(blockContent)
            val initial = TagSuggestionState.Ready(
                blockUuid = blockUuid,
                localSuggestions = localSuggestions,
                llmSuggestions = emptyList(),
                llmPending = engine.hasLlmProvider,
            )
            cache[blockUuid] = initial
            _state.value = initial

            engine.llmSuggest(blockContent, alreadyLinkedTerms).fold(
                ifLeft = { err ->
                    val updated = cache[blockUuid]?.copy(llmError = err.message, llmPending = false)
                    if (updated != null) cache[blockUuid] = updated
                    _state.update { current ->
                        if (current is TagSuggestionState.Ready && current.blockUuid == blockUuid) {
                            current.copy(llmError = err.message, llmPending = false)
                        } else current
                    }
                },
                ifRight = { llmSuggestions ->
                    val updated = cache[blockUuid]?.copy(llmSuggestions = llmSuggestions, llmPending = false)
                    if (updated != null) cache[blockUuid] = updated
                    _state.update { current ->
                        if (current is TagSuggestionState.Ready && current.blockUuid == blockUuid) {
                            current.copy(llmSuggestions = llmSuggestions, llmPending = false)
                        } else current
                    }
                }
            )
            activeBlockUuid = null
        }
    }

    fun dismiss() {
        // Do NOT cancel suggestionJob — let the LLM finish in the background and cache the result.
        // The next requestSuggestions() for the same block will serve from cache immediately.
        _state.value = TagSuggestionState.Idle
    }

    fun close() {
        scope.cancel()
    }
}
