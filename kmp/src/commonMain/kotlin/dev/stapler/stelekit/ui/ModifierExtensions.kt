package dev.stapler.stelekit.ui

import androidx.compose.ui.Modifier

/**
 * Platform-specific pointer input handling for navigation (e.g., mouse back/forward buttons).
 */
expect fun Modifier.platformNavigationInput(
    onBack: () -> Unit,
    onForward: () -> Unit
): Modifier

/**
 * Returns true on platforms where drag gestures should require a long-press first (e.g. Android).
 */
expect fun useLongPressForDrag(): Boolean
