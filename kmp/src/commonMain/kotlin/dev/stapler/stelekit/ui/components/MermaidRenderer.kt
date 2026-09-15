package dev.stapler.stelekit.ui.components

/** Cache key and render-invocation payload for one Mermaid render. */
data class MermaidRenderKey(
    val sourceText: String,
    val theme: ThemeFingerprint,
    val widthPx: Int,
)

/** Per-platform Mermaid rendering entry point. Never throws — failures surface as [MermaidRenderResult.Failed]. */
expect suspend fun renderMermaid(key: MermaidRenderKey): MermaidRenderResult
