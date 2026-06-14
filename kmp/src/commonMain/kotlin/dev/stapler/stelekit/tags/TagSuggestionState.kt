package dev.stapler.stelekit.tags

sealed interface TagSuggestionState {
    data object Idle : TagSuggestionState
    data object Loading : TagSuggestionState
    data class Ready(
        val blockUuid: String,
        val localSuggestions: List<TagSuggestion>,
        val llmSuggestions: List<TagSuggestion>,
        val llmError: String? = null,
    ) : TagSuggestionState
    data class Error(val message: String) : TagSuggestionState
}
