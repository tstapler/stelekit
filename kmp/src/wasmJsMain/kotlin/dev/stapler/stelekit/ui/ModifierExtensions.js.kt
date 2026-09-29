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

// Web is primarily mouse-driven (shift-click, Ctrl+A, bullet lasso-drag all work), and a slow
// click (>~500ms press) was firing long-press-to-select instead of focusing the block, leaving
// the page stuck in selection mode where every later click toggles selection instead of editing.
actual fun useLongPressToSelectBlock(): Boolean = false
