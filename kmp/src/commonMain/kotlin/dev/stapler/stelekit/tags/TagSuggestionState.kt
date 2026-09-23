package dev.stapler.stelekit.tags

sealed interface TagSuggestionState {
    data object Idle : TagSuggestionState
    data object Loading : TagSuggestionState
    data class Ready(
        val blockUuid: String,
        val localSuggestions: List<TagSuggestion>,
        val llmSuggestions: List<TagSuggestion>,
        val llmStatus: LlmSuggestionStatus = LlmSuggestionStatus.NotStarted,
    ) : TagSuggestionState
    data class Error(val message: String) : TagSuggestionState
}

/**
 * Replaces the former flat `llmPending: Boolean` / `llmError: String?` pair on
 * [TagSuggestionState.Ready] — see project_plans/llm-tag-download-stall for the bug this
 * fixes (a frozen "Downloading..." caption with no retry path) and the Pattern Decisions
 * table for why this is a sealed type rather than more flat fields.
 */
sealed interface LlmSuggestionStatus {
    /** Transient — before the first `requestSuggestions()` call for a block resolves its initial state. */
    data object NotStarted : LlmSuggestionStatus

    /** LLM call in flight, or the availability poll loop is active. [caption] is `null` until a
     *  caption string is known (the SDK-sourced reason, then the 45s-escalated string). */
    data class Pending(val caption: String? = null) : LlmSuggestionStatus

    /** Terminal success — real results, or an explicit empty-results outcome. */
    data object Resolved : LlmSuggestionStatus

    /** Poll deadline reached (FR-2) without the model becoming available. Always surfaces a
     *  retry affordance when [retryable] — reaching this state at all implies retry makes sense. */
    data class Stalled(val retryable: Boolean) : LlmSuggestionStatus

    /** A hard provider failure unrelated to on-device availability polling. [retryable] is a
     *  real, non-dead field (see Task 4.2.1): `true` for a `DomainError.NetworkError.Timeout`
     *  (plausibly transient), `false` for an HTTP error, content rejection, or a
     *  genuinely-unsupported-device `Unavailable(retryable=false)`. */
    data class Failed(val message: String, val retryable: Boolean) : LlmSuggestionStatus
}
