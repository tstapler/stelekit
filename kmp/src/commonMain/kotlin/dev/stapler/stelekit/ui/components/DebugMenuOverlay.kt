package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable
import dev.stapler.stelekit.performance.DebugMenuState

@Composable
expect fun DebugMenuOverlay(
    state: DebugMenuState,
    onStateChange: (DebugMenuState) -> Unit,
    onExportBugReport: () -> Unit,
    onDismiss: () -> Unit
)
