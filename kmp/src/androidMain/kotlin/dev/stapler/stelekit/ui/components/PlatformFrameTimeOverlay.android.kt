package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable
import dev.stapler.stelekit.performance.FrameMetric
import dev.stapler.stelekit.performance.JankStatsHolder
import kotlinx.coroutines.flow.StateFlow

@Composable
actual fun PlatformFrameTimeOverlay(isEnabled: Boolean, frameMetric: StateFlow<FrameMetric>) {
    val manager = JankStatsHolder.instance
    if (isEnabled && manager != null) {
        FrameTimeOverlay(manager)
    }
}
