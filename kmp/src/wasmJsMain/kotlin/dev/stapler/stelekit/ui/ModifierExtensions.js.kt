package dev.stapler.stelekit.ui

import androidx.compose.ui.Modifier

actual fun Modifier.platformNavigationInput(
    onBack: () -> Unit,
    onForward: () -> Unit
): Modifier {
    // Browser doesn't have navigation buttons
    return this
}

actual fun useLongPressForDrag(): Boolean = false
