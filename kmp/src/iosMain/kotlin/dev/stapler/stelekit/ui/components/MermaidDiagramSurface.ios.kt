package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * iOS's [renderMermaid] (Null Object, ADR-001) always returns [MermaidRenderResult.UnsupportedPlatform],
 * so [MermaidBlock] never reaches this branch on iOS. A trivial empty [Box] satisfies the
 * expect/actual requirement without pulling in a WebView dependency for an unreachable path.
 */
actual @Composable fun MermaidDiagramSurface(result: MermaidRenderResult.Rendered, modifier: Modifier) {
    Box(modifier)
}
