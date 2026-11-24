// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

import dev.stapler.stelekit.sketch.v2.snap.GridPreset
import dev.stapler.stelekit.sketch.v2.snap.PointSnapKind

/**
 * The strings of the editor host's own surfaces (Task 6.2.0d): the Library tab, the clipboard toasts, the view toggles and the
 * Align and Order menus. Part of the string table like [SketchEditorStrings]; composables read their text from here.
 */
object SketchHostStrings {
    object Library {
        const val SAVE_SELECTION = "Save selection to library"
        const val EMPTY = "The library is empty. Select shapes and save them here to reuse them."
        const val INSERT = "Insert"
        const val DELETE = "Delete"
        const val SAVED_KEY = "LibrarySaved"
        const val SAVED = "Saved to the library"
        const val REFUSED_KEY = "LibraryRefused"
        const val INSERTED_KEY = "LibraryInserted"
        const val INSERTED = "Inserted from the library"
        const val STARTER_TITLE = "Starter symbols (true size)"

        fun defaultName(number: Int): String = "Item $number"

        fun itemDescription(name: String, elements: Int): String = "$name, $elements ${if (elements == 1) "element" else "elements"}"

        fun insertName(name: String): String = "Insert $name"

        fun deleteName(name: String): String = "Delete $name from the library"
    }

    /** The floor-plan modes of the Line tool and the typed wall length. */
    object Plan {
        const val MODE_LINE = "Line"
        const val MODE_WALL = "Wall"
        const val MODE_DOOR = "Door"
        const val MODE_WINDOW = "Window"
        const val MODE_OPENING = "Opening"
        const val DONE = "Done"
        const val DONE_DESCRIPTION = "Finish the wall"
        const val CLOSE_ROOM = "Close room"
        const val CLOSE_ROOM_DESCRIPTION = "Join the last wall back to the first to close the room"
        const val LENGTH = "Length"
        const val LENGTH_DESCRIPTION = "Type the exact length of the next wall"
        const val LENGTH_TITLE = "Length of the next wall"
        const val LENGTH_LABEL = "Length"
        const val LENGTH_SET = "Place wall"
        const val LENGTH_CANCEL = "Cancel"

        fun selected(label: String) = "$label (on)"

        fun modeDescription(label: String, on: Boolean) = if (on) "$label, drawing" else "Draw with $label"
    }

    object Clipboard {
        const val COPIED_KEY = "ClipboardCopied"
        const val COPIED = "Copied"
        const val CUT_KEY = "ClipboardCut"
        const val CUT = "Cut"
        const val NOTHING_TO_COPY_KEY = "ClipboardNothingToCopy"
        const val NOTHING_TO_COPY = "Select something to copy first"
        const val NOTHING_TO_PASTE_KEY = "ClipboardNothingToPaste"
        const val NOTHING_TO_PASTE = "Nothing to paste"
    }

    object Split {
        const val FAILED_KEY = "SplitFailed"
        const val FAILED = "The new sketch could not be created, so nothing was moved."
        const val DONE_KEY = "SplitDone"

        fun done(count: Int): String = "Moved $count ${if (count == 1) "element" else "elements"} to a new sketch."
    }

    object Errors {
        const val EXTRA_FAILED_KEY = "HostExtraFailed"
        const val EXTRA_FAILED = "That did not finish. Your drawing is unchanged."
    }

    object View {
        const val GRID_ON = "Grid on"
        const val GRID_OFF = "Grid off"
        const val SNAP_ON = "Snap on"
        const val SNAP_OFF = "Snap off"
        const val SNAP_PAUSED = "Snap paused"
        const val SNAP_PAUSE_ACTION = "Pause snapping"
        const val SNAP_RESUME_ACTION = "Resume snapping"
        const val GRID_KEY = "ViewGrid"
        const val SNAP_KEY = "ViewSnap"
    }

    /** The Snap section of the overflow menu and the names shown beside a snapped point. */
    object Snap {
        const val SECTION = "Snap to"
        const val EXPANDED = "expanded"
        const val COLLAPSED = "collapsed"
        const val EXPAND_MARK = "+"
        const val COLLAPSE_MARK = "-"
        const val ALIGNMENT = "Edges and centres"
        const val GRID_STEP = "Grid step"
        const val ON = "on"
        const val OFF = "off"

        fun kindName(kind: PointSnapKind): String = when (kind) {
            PointSnapKind.Endpoint -> "Endpoint"
            PointSnapKind.Intersection -> "Intersection"
            PointSnapKind.Midpoint -> "Midpoint"
            PointSnapKind.Centre -> "Centre"
            PointSnapKind.Perpendicular -> "Perpendicular"
            PointSnapKind.Tangent -> "Tangent"
        }

        fun presetName(preset: GridPreset): String = when (preset) {
            GridPreset.Sixteenth -> "1/16 in"
            GridPreset.Quarter -> "1/4 in"
            GridPreset.Inch -> "1 in"
            GridPreset.SixInch -> "6 in"
            GridPreset.Foot -> "1 ft"
        }

        fun gridRow(preset: GridPreset): String = "$GRID_STEP: ${presetName(preset)}"

        fun gridRowDescription(preset: GridPreset): String = "$GRID_STEP ${presetName(preset)}, tap for ${presetName(preset.next())}"

        fun toggleDescription(name: String, on: Boolean): String = "Snap to $name ${if (on) ON else OFF}"
    }

    object Align {
        const val TITLE = "Align"
        const val CLOSE = "Close"
        const val LEFT = "Align left"
        const val CENTER_HORIZONTAL = "Align centres horizontally"
        const val RIGHT = "Align right"
        const val TOP = "Align top"
        const val CENTER_VERTICAL = "Align centres vertically"
        const val BOTTOM = "Align bottom"
        const val DISTRIBUTE_HORIZONTAL = "Distribute horizontally"
        const val DISTRIBUTE_VERTICAL = "Distribute vertically"
    }

    object Order {
        const val TITLE = "Order"
        const val CLOSE = "Close"
    }

    object Transform {
        const val CLOSE = "Close"
        const val COMMITTED_KEY = "TransformCommitted"
    }

    object ConstrainMenu {
        const val TITLE = "Constrain"
        const val CLOSE = "Close"
    }

    object Dimension {
        const val SUGGESTION_ANNOUNCE_KEY = "DimensionSuggestion"
    }
}
