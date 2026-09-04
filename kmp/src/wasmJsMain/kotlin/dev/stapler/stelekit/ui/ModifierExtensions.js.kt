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

// Unchanged from prior behavior: Web can be mouse- or touch-driven, and unlike JVM/desktop it
// has no confirmed report of this misfiring — leave long-press-to-select as the multi-select
// entry point here rather than guessing.
actual fun useLongPressToSelectBlock(): Boolean = true
