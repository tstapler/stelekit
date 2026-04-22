package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable
import dev.stapler.stelekit.performance.FrameMetric
import kotlinx.coroutines.flow.StateFlow

@Composable
actual fun PlatformFrameTimeOverlay(isEnabled: Boolean, frameMetric: StateFlow<FrameMetric>) {
}
