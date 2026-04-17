package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformFrameTimeOverlay(isEnabled: Boolean) {
    // Frame time overlay requires JankStats (Android-only); no-op on desktop
}
