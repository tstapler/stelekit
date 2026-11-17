// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

/**
 * Names the accessibility audit prints when a token or a focused node fails (Story 3.6.6, UX-46, UX-125): the audit's
 * failure messages cite them, so they live in the table with every other string of the v2 UI.
 */
object SketchA11yStrings {
    /** The web media query behind the reduce-motion signal (UX-47, UX-113). */
    const val REDUCED_MOTION_QUERY = "(prefers-reduced-motion: reduce)"

    object Token {
        const val TEXT_ON_CANVAS = "text on canvas"
        const val TEXT_ON_SURFACE = "text on surface"
        const val HINT_ON_CANVAS = "hint text on canvas"
        const val HINT_ON_SURFACE = "hint text on surface"
        const val TEXT_ON_PANEL = "text on panel"
        const val PRIMARY_ON_SURFACE = "primary text on surface"
        const val PRIMARY_ON_PANEL = "primary text on panel"
        const val TEXT_ON_PRIMARY = "text on primary button"
        const val TOAST_AND_POPOVER = "toast and popover text"
        const val ERROR_TEXT = "error text"
        const val HANDLE_AND_GLYPH = "handle and glyph on surface"
        const val GLYPH_ON_PANEL = "glyph on panel"
        const val STROKE_ON_SURFACE = "panel stroke on surface"
        const val STROKE_ON_PANEL = "panel stroke on panel"
        const val RING_HIGHLIGHT = "selection and ring highlight"
        const val RING_INNER = "focus ring inner on surface"
        const val RING_OUTER = "focus ring outer on inverse"
    }

    object Overlay {
        const val VIEW_CLUSTER = "view cluster"
        const val CHIP_ROW = "action chip row"
        const val TOASTS = "toasts"
        const val SHEET = "bottom sheet"
        const val KEYBOARD = "soft keyboard"

        fun edgeStrip(number: Int) = "edge strip $number"
    }
}
