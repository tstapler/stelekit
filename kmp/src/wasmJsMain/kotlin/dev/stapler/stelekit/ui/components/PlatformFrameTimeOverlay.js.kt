package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformFrameTimeOverlay(isEnabled: Boolean) {
    // Frame time overlay is not available on wasmJs
}
