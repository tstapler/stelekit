package dev.stapler.stelekit.ui.components

// v1 defers Mermaid rendering on iOS; the raw-fallback CodeFenceBlock path handles display.
actual suspend fun renderMermaid(key: MermaidRenderKey): MermaidRenderResult = MermaidRenderResult.UnsupportedPlatform
