// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

/** The on-canvas dimension pills of a technical sheet and the chips that reach the technical tools. */
object SketchDimensionStrings {
    object Pill {
        const val WIDTH = "Width"
        const val HEIGHT = "Height"
        const val DIAMETER = "Diameter"
        const val LENGTH = "Length"
        const val RADIUS = "Radius"
        const val WIDTH_SYMBOL = "W"
        const val HEIGHT_SYMBOL = "H"
        const val DIAMETER_SYMBOL = "Ø"
        const val LENGTH_SYMBOL = "L"
        const val RADIUS_SYMBOL = "R"
        const val LOCKED = "Locked. Unlock the shape to change its size."
        const val CANCEL = "Cancel size edit"
        const val REFUSED = "That size was not accepted."

        fun description(label: String, value: String) = "$label, $value. Tap to type a new size."

        const val APPLY_HINT = "Done applies. Tap outside to cancel."

        fun nextHint(next: String) = "Next goes to $next. Tap outside to cancel."
    }

    object Chips {
        const val DIMENSIONS = "Dimensions"
        const val DIMENSIONS_OFF = "Dimensions off"
        const val DIMENSIONS_HIDE = "Hide dimension pills"
        const val DIMENSIONS_SHOW = "Show dimension pills"
        const val CONSTRAINTS = "Constraints"
        const val CONSTRAINTS_DESCRIPTION = "Open the Constraints panel"
        const val TECHNICAL = "Tech data"
        const val TECHNICAL_DESCRIPTION = "Open the Technical data panel"
    }
}
