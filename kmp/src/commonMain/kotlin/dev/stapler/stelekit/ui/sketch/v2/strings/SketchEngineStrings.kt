// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

/**
 * The strings of the surfaces that put the engine's commands in front of the user: shape and arrow labels, freeing an arrow end,
 * editing a line's points and the sheet style. Part of the string table like [SketchHostStrings]; composables read their text here.
 */
object SketchEngineStrings {
    object SelectionMenu {
        const val EDIT_LABEL = "Edit label..."
        const val EDIT_POINTS = "Edit points"
        const val UNBIND_START = "Free start"
        const val UNBIND_END = "Free end"
    }

    object Label {
        const val TITLE_SHAPE = "Shape label"
        const val TITLE_ARROW = "Arrow label"
        const val FIELD = "Label text"
        const val SAVE = "Save"
        const val CANCEL = "Cancel"
        const val REMOVE = "Remove label"
        const val TOO_LONG_KEY = "LabelTooLong"
    }

    object Unbind {
        const val DONE_KEY = "ArrowEndFreed"
        const val DONE = "Arrow end freed"
    }

    object Constrain {
        const val HORIZONTAL_DISTANCE = "Horizontal distance"
        const val VERTICAL_DISTANCE = "Vertical distance"
    }

    object SheetStyle {
        const val TITLE = "Sheet style"
        const val CLEAN = "Clean"
        const val SKETCHY = "Sketchy"
    }

    object SketchCopy {
        const val ACTION = "Copy to graph..."
        const val PICK_TITLE = "Copy sketch to which graph?"
        const val CANCEL = "Cancel"
        const val COPYING = "Copying..."
    }

    object LinePoints {
        const val HINT_KEY = "LinePointsEditing"
        const val HINT = "Drag a point to move it, drag a segment to add one, press Esc to finish"
    }
}
