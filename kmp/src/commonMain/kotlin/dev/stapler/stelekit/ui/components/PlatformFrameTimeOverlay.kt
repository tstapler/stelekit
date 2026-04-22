package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable
import dev.stapler.stelekit.performance.FrameMetric
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-specific frame-time debug overlay.
 *
 * On Android: renders [FrameTimeOverlay] using [JankStatsHolder] when [isEnabled] is true.
 * On JVM desktop: renders a lightweight overlay using [frameMetric].
 */
@Composable
expect fun PlatformFrameTimeOverlay(
    isEnabled: Boolean,
    frameMetric: StateFlow<FrameMetric>,
)
