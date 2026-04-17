package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable
import dev.stapler.stelekit.performance.JankStatsHolder

@Composable
actual fun PlatformFrameTimeOverlay(isEnabled: Boolean) {
    val manager = JankStatsHolder.instance
    if (isEnabled && manager != null) {
        FrameTimeOverlay(manager)
    }
}
