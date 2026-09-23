package dev.stapler.stelekit.tags

/**
 * A single tag suggestion produced by either the local AhoCorasick scan (LOCAL)
 * or the LLM tier (LLM).
 *
 * [confidence] is in the range 0.0–1.0:
 *   - LOCAL exact hits always have confidence = 1.0
 *   - LLM positional decay produces values in [0.50, 0.85]
 */
data class TagSuggestion(
    val term: String,           // exact page name from graph vocabulary
    val confidence: Float,      // 0.0–1.0; >=0.95 triggers auto-apply (LOCAL only)
    val source: Source,
    val autoApplied: Boolean = false,
) {
    enum class Source { LOCAL, LLM }
}
