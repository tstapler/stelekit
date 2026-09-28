package dev.stapler.stelekit.tags

data class TagSuggestionRequest(
    val blockUuid: String,
    val blockContent: String,
    val pageVocabulary: List<String>,   // token-filtered candidate page names
)
