// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

/**
 * The strings of the import and export surfaces (Stories 5.1.5 and 5.2.3; ux.md sections 11 and 12). Part of the string
 * table (`ui/sketch/v2/strings/`, like [SketchMigrationStrings]): composables and models read their text from here.
 * Counts and file names are arguments of a function, never concatenated at a call site.
 */
object SketchImportExportStrings {
    object Import {
        const val MENU_ITEM = "Import .excalidraw..."
        const val CANCEL = "Cancel"
        const val CLOSE = "Close"
        const val DONE = "Done"
        const val CHOOSE_ANOTHER = "Choose another file"
        const val COPY_DETAILS = "Copy details"
        const val UNDO_IMPORT = "Undo import"
        const val IMPORT_ANYWAY = "Import"
        const val DETAILS_HEADING = "Details (reason codes)"
        const val SUMMARY_TITLE = "Import finished"
        const val UNAVAILABLE_TITLE = "Can't import here"
        const val NOT_EXCALIDRAW = "Couldn't read this file: not valid Excalidraw JSON."
        const val CANCELLED = "Import cancelled. Nothing was changed."
        const val FAILED = "Import failed unexpectedly. Nothing was changed."
        const val IMAGE_SAVE_FAILED = "Couldn't save an image. Nothing was changed."
        const val NEW_SKETCH_FAILED = "Couldn't create the new sketch. Nothing was changed."
        const val SHEET_CHANGED = "The sketch changed while importing. Nothing was imported."
        const val NO_PICKER = "This device can't choose a file here yet."
        const val CAP_REACHED = "This sketch is full, so nothing can be added to it."
        const val NEWER_SCHEMA = "Created by a newer Excalidraw; some elements may differ."
        const val LINKED_IMAGES = "Linked images were not downloaded."
        const val TIMEOUT = "This file took too long to read. Nothing was changed."
        const val FINISHED_SUMMARY = "Import finished."
        const val FINISHED_SHORT = "Imported"
        const val FINISHED_ACTION = "Details"
        const val UNDONE_SUMMARY = "Import undone"
        const val FINISHED_KEY = "ImportFinished"
        const val UNDONE_KEY = "ImportUndone"
        const val OVER_CAP_NEW_SKETCH_UNAVAILABLE = "A new sketch can't be made here."
        const val OVER_CAP_FIRST_UNAVAILABLE = "This sketch has no room left."

        fun megabytes(tenths: Int): String = "${tenths / TENTHS}.${tenths % TENTHS} MB"

        fun progressTitle(name: String): String = "Importing \"$name\""

        fun reading(size: String): String = "Reading $size ..."

        fun tooLarge(megabytes: Long): String = "Files over $megabytes MB aren't imported."

        fun reasonLine(code: String): String = "Reason: $code"

        fun overCap(elements: Int?, limit: Int): String =
            if (elements == null) {
                "This drawing has more than ${grouped(limit)} elements; the limit is ${grouped(limit)}."
            } else {
                "This drawing has ${grouped(elements)} elements; the limit is ${grouped(limit)}."
            }

        fun overRoom(elements: Int, room: Int): String =
            "This drawing has ${grouped(elements)} elements; this sketch has room for ${grouped(room.coerceAtLeast(0))}."

        fun importFirst(count: Int): String = "Import first ${grouped(count)}"

        const val IMPORT_NEW_SKETCH = "Import into a new sketch"

        fun imported(imported: Int, total: Int): String = "Imported $imported of $total elements."

        fun overLimit(count: Int): String = "${countOf(count, "element")} over the sketch limit"

        fun skipped(parts: List<String>): String = "Skipped: ${parts.joinToString(LIST_SEPARATOR)}"

        fun placeholders(parts: List<String>): String = "Kept as grey placeholders: ${parts.joinToString(LIST_SEPARATOR)}"

        fun changed(parts: List<String>): String = "Changed: ${parts.joinToString(LIST_SEPARATOR)}"

        fun imagesMissing(count: Int): String = "${countOf(count, "image")} could not be shown."

        fun countOf(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

        const val INVALID_ELEMENT = "invalid element"
        const val UNSUPPORTED_ARROWHEAD = "unsupported arrowhead"
        const val UNKNOWN_FILL = "unknown fill"
        const val UNKNOWN_STROKE_STYLE = "unknown stroke style"

        private fun grouped(value: Int): String = value.toString().reversed().chunked(GROUP_DIGITS).joinToString(",").reversed()

        private const val GROUP_DIGITS = 3
        private const val LIST_SEPARATOR = ", "
        private const val TENTHS = 10
    }

    /** Reason codes of the import details and the log: tokens, not user prose, kept here so no literal sits outside the table. */
    object Codes {
        const val UNEXPECTED = "Unexpected"
        const val CAPACITY_EXCEEDED = "CapacityExceeded"
        const val IMAGE_SAVE_FAILED = "ImageSaveFailed"
        const val NEW_SKETCH_FAILED = "NewSketchFailed"
        const val DELETED = "DeletedElement"
        const val UNKNOWN_TYPE = "UnknownElementType"
        const val UNSUPPORTED = "UnsupportedElement"
        const val MISSING_IMAGE = "MissingImage"
        const val Z_ORDER = "ZOrderFromArray"
        const val NEWER_SCHEMA = "NewerSchema"
        const val NO_FILE_DATA = "NoFileData"
        const val LINK_NOT_DOWNLOADED = "LinkNotDownloaded"

        fun head(imported: Int, total: Int): String = "imported=$imported total=$total"

        fun line(code: String, count: Int): String = "$code $count"

        fun tagged(code: String, detail: String): String = "$code:$detail"
    }

    object Export {
        const val MENU_ITEM = "Export..."
        const val TITLE = "Export"
        const val FORMAT = "Format"
        const val SCOPE = "Scope"
        const val SCALE = "Scale"
        const val STYLE = "Style"
        const val DIMENSIONS = "Dimensions"
        const val PREVIEW = "Preview"
        const val BACKGROUND = "Background"
        const val CANCEL = "Cancel"
        const val EXPORT_BUTTON = "Export"
        const val RETRY = "Retry"
        const val USE_1X = "Use 1x"
        const val TRY_AGAIN = "Try again"
        const val CHOOSE_ANOTHER_LOCATION = "Choose another location"

        const val FORMAT_PNG = "PNG"
        const val FORMAT_SVG = "SVG"
        const val FORMAT_PDF = "PDF"
        const val FORMAT_EXCALIDRAW = ".excalidraw"
        const val FORMAT_LIBRARY = ".excalidrawlib"
        const val FORMAT_CSV = "CSV (technical)"

        const val SCOPE_WHOLE = "Whole sketch"
        const val SCOPE_SELECTION = "Selection"
        const val SCOPE_FRAME = "Frame"

        const val SCALE_1X = "1x"
        const val SCALE_2X = "2x"
        const val SCALE_3X = "3x"

        const val STYLE_AS_DRAWN = "As drawn"
        const val STYLE_CLEAN = "Clean"

        const val DIMENSIONS_SHEET = "Sheet setting"
        const val DIMENSIONS_ON = "Show"
        const val DIMENSIONS_OFF = "Hide"

        const val SELECTION_DISABLED = "Select elements first"
        const val FRAME_DISABLED = "This sketch has no frames"
        const val LIBRARY_EMPTY = "The library is empty"
        const val CSV_DISABLED = "Only sketches with dimensions"
        const val NOTHING_TO_EXPORT = "Nothing to export."
        const val LAYERS_FLATTENED = "Layers are flattened in the exported file (ADR-006)."
        const val ENCODE_FAILED = "Couldn't create the image. Try a smaller scale."
        const val UNWRITABLE = "Couldn't save the file."
        const val BLOCKED = "Your browser blocked the download."
        const val FAILED = "Export failed unexpectedly. Nothing was changed."
        const val FRAME_UNNAMED = "Frame"

        const val PAGE = "Page"
        const val PAGE_ON = "Print on a page at the sheet's scale"
        const val PAPER = "Paper"
        const val PAPER_CUSTOM = "Custom size (for example 300x200mm or 8x10in)"
        const val ORIENTATION = "Orientation"
        const val PORTRAIT = "Portrait"
        const val LANDSCAPE = "Landscape"
        const val MARGINS = "Margins"
        const val MARGIN_NARROW = "Narrow"
        const val MARGIN_NORMAL = "Normal"
        const val MARGIN_WIDE = "Wide"
        const val DRAWING_SCALE = "Drawing scale"
        const val SCALE_CUSTOM = "Custom ratio (for example 1:37 or 1/4\" = 1')"
        const val FIT_TO_PAGE = "Fit to page"
        const val SCALE_BAR = "Scale bar"
        const val NORTH_ARROW = "North arrow"
        const val NORTH_ANGLE = "North direction (degrees clockwise from up)"
        const val TITLE_BLOCK = "Title block"
        const val FIELD_TITLE = "Title"
        const val FIELD_AUTHOR = "Author"
        const val FIELD_DATE = "Date"
        const val FIELD_SHEET = "Sheet number"
        const val FIELD_NOTES = "Notes"
        const val RESOLUTION = "Resolution"
        const val TILE_PAGES = "Tile across several pages"
        const val USE_LOW_DPI = "Use 72 dpi"
        const val CUSTOM_BUTTON = "Custom..."
        const val TITLE_BLOCK_EDIT = "Edit title block..."
        const val APPLY = "Apply"

        fun degrees(value: Int): String = "$value deg"

        fun dpi(value: Int): String = "$value dpi"

        fun paperName(paper: String, landscape: dev.stapler.stelekit.sketch.v2.export.Orientation): String =
            "$paper ${if (landscape == dev.stapler.stelekit.sketch.v2.export.Orientation.Landscape) LANDSCAPE.lowercase() else PORTRAIT.lowercase()}"

        fun fits(scale: String, paper: String, need: String, room: String): String = "Fits at $scale on $paper. Drawing $need; drawing area $room."

        fun overflow(scale: String, paper: String, need: String, room: String, columns: Int, rows: Int): String =
            "Does not fit at $scale on $paper. Drawing $need; drawing area $room, so it overflows the page" +
                " (it needs $columns by $rows pages). Without tiling it is cut off at the margins."

        fun suggestion(scale: String): String = "Fits at $scale."

        const val NO_FIT = "No standard scale fits this paper. Use bigger paper or a PDF tiled across pages."

        fun tooManyPages(pages: Int, budget: Int): String = "Tiling would take $pages pages (the most is $budget). Choose a smaller scale or bigger paper."

        fun tooWide(widthPx: Long): String = "This image would be ${grouped(widthPx)} px wide. Choose 1x or export a frame."

        fun tooTall(heightPx: Long): String = "This image would be ${grouped(heightPx)} px tall. Choose 1x or export a frame."

        fun tooManyPixels(widthPx: Long, heightPx: Long): String = "This image would be ${grouped(widthPx)} by ${grouped(heightPx)} px. Choose 1x or export a frame."

        fun previewSize(widthPx: Long, heightPx: Long): String = "${grouped(widthPx)} x ${grouped(heightPx)} px"

        fun missingImages(count: Int): String =
            if (count == 1) "1 missing image exported as placeholder" else "$count missing images exported as placeholders"

        fun frameName(name: String?, position: Int): String = name?.takeIf { it.isNotBlank() } ?: "$FRAME_UNNAMED $position"

        fun fileName(baseName: String, extension: String): String = "$baseName.$extension"

        const val PLACE_CHOSEN = "the place you chose"
        const val EXPORT_SAVED_KEY = "ExportSaved"
        const val EXPORT_SAVED_SUMMARY = "File exported."
        const val EXPORT_SAVED_SHORT = "Exported"
        const val EXPORT_SAVED_OPEN = "Open"
        const val EXPORT_SAVED_COPY_PATH = "Copy path"
        const val EXPORT_SAVED_WEB_KEY = "ExportSavedWeb"
        const val EXPORT_SAVED_WEB_SUMMARY = "File downloaded."
        const val EXPORT_SAVED_WEB_SHORT = "Download"

        fun savedDetails(name: String, place: String): String = "$name in $place"

        fun savedWebDetails(name: String): String = "$name in Downloads. Check your downloads."

        private fun grouped(value: Long): String = value.toString().reversed().chunked(GROUP_DIGITS).joinToString(",").reversed()

        private const val GROUP_DIGITS = 3
    }
}
