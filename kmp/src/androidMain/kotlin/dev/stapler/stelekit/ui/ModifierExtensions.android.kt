package dev.stapler.stelekit.ui

import androidx.compose.ui.Modifier

actual fun Modifier.platformNavigationInput(
    onBack: () -> Unit,
    onForward: () -> Unit
): Modifier = this // No-op for Android as mouse button navigation is typically not used

actual fun useLongPressForDrag(): Boolean = true

// Unchanged behavior: the gutter's drag handle already claims long-press for reorder here,
// so block-content long-press-to-select would race it — selection entry stays gutter-driven.
actual fun useLongPressToSelectBlock(): Boolean = false
