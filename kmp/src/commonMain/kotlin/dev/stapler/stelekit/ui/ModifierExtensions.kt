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

/**
 * Returns true on platforms where long-pressing a block's content is the primary way to enter
 * multi-select mode. False on platforms that already have a precise pointer-based alternative
 * (shift-click range select, Ctrl/Cmd+A, click-and-drag lasso-select) — there, a stationary
 * press-and-hold is common ordinary behavior (e.g. positioning a text cursor before typing) and
 * misfiring into selection mode on every such pause was reported as a real usability bug.
 */
expect fun useLongPressToSelectBlock(): Boolean
