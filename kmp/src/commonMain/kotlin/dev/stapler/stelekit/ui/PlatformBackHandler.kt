package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable

/**
 * Platform-specific back handler.
 * On Android: integrates with the system back gesture and Predictive Back (API 34+).
 * On other platforms: no-op (back is handled via keyboard shortcuts).
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
