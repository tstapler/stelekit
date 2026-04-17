package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop: back handled via keyboard shortcuts (Alt+Left / Cmd+[)
}
