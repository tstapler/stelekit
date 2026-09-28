package dev.stapler.stelekit.ui.components

/** Cache key and render-invocation payload for one Mermaid render. */
data class MermaidRenderKey(
    val sourceText: String,
    val theme: ThemeFingerprint,
    val widthPx: Int,
)

/** Per-platform Mermaid rendering entry point. Never throws — failures surface as [MermaidRenderResult.Failed]. */
expect suspend fun renderMermaid(key: MermaidRenderKey): MermaidRenderResult

/**
 * Blank/oversized-source guard shared by every platform's [renderMermaid] actual, so the three
 * copies can't drift in wording or behavior. `MermaidBlock` already gates on this before ever
 * calling a renderer, so in practice this only fires for a caller that bypasses it (tests, or a
 * future direct caller) — still worth enforcing once, not per platform.
 */
internal fun MermaidRenderKey.sourceLengthFailure(): MermaidRenderResult.Failed? = when {
    sourceText.isBlank() -> MermaidRenderResult.Failed("empty source")
    sourceText.length > MAX_MERMAID_SOURCE_LENGTH ->
        MermaidRenderResult.Failed("source exceeds $MAX_MERMAID_SOURCE_LENGTH chars")
    else -> null
}
