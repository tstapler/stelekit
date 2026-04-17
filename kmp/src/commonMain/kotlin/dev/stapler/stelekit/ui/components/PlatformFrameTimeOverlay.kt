package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable

/**
 * Platform-specific frame-time debug overlay.
 *
 * On Android: renders [FrameTimeOverlay] using [JankStatsHolder] when [isEnabled] is true.
 * On JVM desktop: no-op (JankStats is Android-only).
 */
@Composable
expect fun PlatformFrameTimeOverlay(isEnabled: Boolean)
