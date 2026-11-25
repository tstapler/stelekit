// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The two OS-level visual preferences the table surfaces honour (ux.md 3.7; UX-63, UX-64): [reducedMotion] (no
 * animation, scroll jumps) and [highContrast] (forced colors: markers by outline width and glyph on black or white,
 * never a tint or palette alone). Platform entry points provide them; the default is off everywhere.
 */
@Immutable
data class TableVisualPrefs(val reducedMotion: Boolean = false, val highContrast: Boolean = false)

@Suppress("CompositionLocalAllowlist") // provided once per platform entry point; off when nobody provides it
val LocalTableVisualPrefs = staticCompositionLocalOf { TableVisualPrefs() }

/** Outline widths in dp: the 2dp floor of ux.md 3.5, thicker under high contrast so it survives a palette swap. */
internal object OutlineWidths {
    const val NORMAL_DP = 2f
    const val HIGH_CONTRAST_DP = 4f

    fun of(prefs: TableVisualPrefs): Float = if (prefs.highContrast) HIGH_CONTRAST_DP else NORMAL_DP
}

/** Black on a light surface and white on a dark one: the highest contrast the surface allows. */
internal fun highContrastInk(surface: Color): Color = if (contrastRatio(Color.Black, surface) >= contrastRatio(Color.White, surface)) Color.Black else Color.White

/** Glyph drawn on an error cell under high contrast, so the state is not carried by the error color alone. */
internal const val ERROR_GLYPH = "!"
