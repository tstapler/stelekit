package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Displays an already-[MermaidRenderResult.Rendered] diagram using each platform's
 * cheapest-correct rendering mechanism (ADR-001): Skia `Canvas` on JVM, a DOM overlay on
 * wasmJs, a `WebView` on Android, and a trivial no-op placeholder on iOS — whose
 * [renderMermaid] never produces a [MermaidRenderResult.Rendered] to pass here, but Kotlin
 * still requires an `actual` for every declared target.
 */
@Composable
expect fun MermaidDiagramSurface(result: MermaidRenderResult.Rendered, modifier: Modifier = Modifier)
