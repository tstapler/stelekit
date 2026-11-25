// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

/** The "Sketch outline" list and the "Add shape at..." form: the gesture-free ways to read and build a sheet (E12, WCAG 2.1.1, 1.3.2). */
object SketchOutlineStrings {
    const val OUTLINE_MENU_ITEM = "Sketch outline..."
    const val OUTLINE_TITLE = "Sketch outline"
    const val OUTLINE_EMPTY = "The sketch has no elements"
    const val CLOSE = "Close"
    const val SELECT_ACTION = "Select"
    const val RENAME_ACTION = "Rename"
    const val RENAME_FIELD = "Name"
    const val RENAME_SAVE = "Save name"
    const val RENAME_CANCEL = "Cancel"
    const val SELECTED_SUFFIX = "selected"

    /** "Item 3 of 12: Rectangle 1, 3 feet by 2 feet". */
    fun row(position: Int, total: Int, label: String, selected: Boolean): String =
        "Item $position of $total: $label" + if (selected) ", $SELECTED_SUFFIX" else ""

    fun summary(total: Int): String = if (total == 1) "1 element, top to bottom" else "$total elements, top to bottom"

    fun selectedAnnouncement(label: String): String = "Selected: $label"

    const val ADD_MENU_ITEM = "Add shape at..."
    const val ADD_TITLE = "Add shape at coordinates"
    const val ADD_HELP = "X and Y place the shape's top-left corner, measured from the sheet origin."
    const val ADD_CONFIRM = "Add shape"
    const val ADD_CANCEL = "Cancel"
    const val KIND_LABEL = "Shape"
    const val KIND_RECTANGLE = "Rectangle"
    const val KIND_ELLIPSE = "Ellipse"
    const val KIND_DIAMOND = "Diamond"
    const val FIELD_X = "X"
    const val FIELD_Y = "Y"
    const val FIELD_WIDTH = "Width"
    const val FIELD_HEIGHT = "Height"

    fun unitHint(unitName: String): String = "Lengths are typed in $unitName; a unit such as 3' 7\" or 25 mm is also accepted."

    fun fieldError(field: String, reason: String): String = "$field: $reason"
    const val NOT_A_NUMBER = "Type a number."
    const val NOT_POSITIVE = "Must be more than zero."
    const val OUT_OF_RANGE = "That is too far from the origin."

    fun added(kind: String, x: String, y: String, width: String, height: String): String =
        "Added $kind at $x, $y, $width by $height"

    /** TalkBack custom actions on the canvas node. */
    const val ACTION_OUTLINE = "Open sketch outline"
    const val ACTION_ADD_SHAPE = "Add shape at coordinates"
}
