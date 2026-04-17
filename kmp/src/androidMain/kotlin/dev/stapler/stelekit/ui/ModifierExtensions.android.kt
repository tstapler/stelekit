package dev.stapler.stelekit.ui

import androidx.compose.ui.Modifier

actual fun Modifier.platformNavigationInput(
    onBack: () -> Unit,
    onForward: () -> Unit
): Modifier = this // No-op for Android as mouse button navigation is typically not used

actual fun useLongPressForDrag(): Boolean = true
