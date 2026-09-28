package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

actual @Composable fun MermaidDiagramSurface(result: MermaidRenderResult.Rendered, modifier: Modifier) {
    MermaidWebViewHost(result, modifier)
}
