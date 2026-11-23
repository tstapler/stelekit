// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

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

        fun defaultName(number: Int): String = "Item $number"

        fun itemDescription(name: String, elements: Int): String = "$name, $elements ${if (elements == 1) "element" else "elements"}"

        fun insertName(name: String): String = "Insert $name"

        fun deleteName(name: String): String = "Delete $name from the library"
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
        const val GRID_KEY = "ViewGrid"
        const val SNAP_KEY = "ViewSnap"
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
