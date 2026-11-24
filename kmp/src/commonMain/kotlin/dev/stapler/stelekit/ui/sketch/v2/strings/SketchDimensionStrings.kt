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

    /** The always-on dimension lines of a technical sheet. */
    object Annotation {
        fun description(label: String, element: String, spoken: String) = "$label of $element: $spoken"

        fun editAction(label: String) = "Edit $label"
    }

    /** A length in words, for a screen reader. */
    object Spoken {
        const val FOOT = "foot"
        const val FEET = "feet"
        const val INCH = "inch"
        const val INCHES = "inches"
        const val AND = "and"
        const val OF_AN_INCH = "of an inch"

        fun denominator(d: Long, plural: Boolean): String = when (d) {
            2L -> if (plural) "halves" else "half"
            4L -> if (plural) "quarters" else "quarter"
            8L -> if (plural) "eighths" else "eighth"
            else -> if (plural) "sixteenths" else "sixteenth"
        }
    }

    object Chips {
        const val DIMENSIONS = "Dimensions"
        const val DIMENSIONS_OFF = "Dimensions off"
        const val DIMENSIONS_HIDE = "Hide dimensions"
        const val DIMENSIONS_SHOW = "Show dimensions"
        const val SCOPE_ALL = "Show: all"
        const val SCOPE_ALL_DESCRIPTION = "Dimensions show on every shape. Tap to show only the selected shapes."
        const val SCOPE_SELECTED = "Show: selected"
        const val SCOPE_SELECTED_DESCRIPTION = "Dimensions show on the selected shapes only. Tap to show every shape."
        const val CONSTRAINTS = "Constraints"
        const val CONSTRAINTS_DESCRIPTION = "Open the Constraints panel"
        const val TECHNICAL = "Tech data"
        const val TECHNICAL_DESCRIPTION = "Open the Technical data panel"
    }

    /** The symbol keys above the soft keyboard of the size field. */
    object Keys {
        const val FEET_MARK_LABEL = "'"
        const val INCH_MARK_LABEL = "\""
        const val SLASH_LABEL = "/"
        const val POINT_LABEL = "."
        const val FEET_WORD_LABEL = "ft"
        const val INCH_WORD_LABEL = "in"
        const val FEET_MARK = "feet mark"
        const val INCH_MARK = "inch mark"
        const val SLASH = "fraction slash"
        const val POINT = "decimal point"
        const val FEET_WORD = "ft, feet"
        const val INCH_WORD = "in, inches"
    }

    /** The length display switch (feet and inches, inches, decimal inches) and the live reading of typed text. */
    object Units {
        const val CHIP_FEET_INCHES = "ft-in"
        const val CHIP_INCHES = "in"
        const val CHIP_DECIMAL = "in.dec"
        const val NAME_FEET_INCHES = "Feet and inches"
        const val NAME_INCHES = "Inches"
        const val NAME_DECIMAL = "Decimal inches"
        const val MENU_LABEL = "Length units"
        const val MENU_CHOOSE = "Length units, tap to change"

        fun chipRowLabel(chip: String) = "Units: $chip"

        fun chipDescription(current: String, next: String) = "Length units: $current. Tap to switch to $next."

        fun menuText(current: String) = "$MENU_LABEL: $current"

        fun menuDescription(current: String, next: String) = "$MENU_LABEL: $current. Tap to switch to $next."

        /** The live reading under a field: the typed text as the app will commit it, in the chosen units first. */
        fun reading(primary: String, other: String?) = if (other == null) "= $primary" else "= $primary ($other)"

        fun rounded(written: String, nearest: String) = "$written is between sixteenths. Rounded to $nearest"

        fun roundedShort(nearest: String) = "Rounded to $nearest"
    }
}
