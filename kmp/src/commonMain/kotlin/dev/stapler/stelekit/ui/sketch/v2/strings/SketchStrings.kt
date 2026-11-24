// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource
import dev.stapler.stelekit.sketch.v2.tools.ToolId

/**
 * The string table of the v2 editor UI (Story 3.6.8, Task 3.6.8d, UX-95): every user-visible string a `UI2` composable shows
 * comes from here, so wording lives in one place and `HardcodedStringAuditTest` can fail on a literal outside it. Text that
 * is dynamic (a count, a name) is built by a function in this table, never inline in a composable. Surfaces are migrated
 * into it as they are touched; the audit baseline lists the files that still hold literals and may only shrink.
 */
object SketchStrings {
    /** The overflow switch for the touch magnifier. */
    object Loupe {
        const val TOGGLE = "Touch magnifier"
        const val TOGGLE_ON = "Touch magnifier, on"
        const val TOGGLE_OFF = "Touch magnifier, off"
        const val STATE_ON = "on"
        const val STATE_OFF = "off"
    }

    object Empty {
        const val PROMPT = "Pick a tool and draw, or import an .excalidraw file"
        const val IMPORT = "Import"
        const val SHORTCUTS = "Keyboard shortcuts"
    }

    /** Fixed strings of the `CoachMark` banner row (ux.md chrome table row 13); a test compares them with the table. */
    object CoachMark : BannerStringSource {
        const val SUMMARY = "New here? Quick tips."
        const val SHORT_TITLE = "Tips"
        const val PRIMARY = "Show me"
        const val GOT_IT = "Got it"
        const val DISMISS = "Dismiss tips"

        override fun summary(row: BannerRowId): String = owned(row, SUMMARY)

        override fun shortTitle(row: BannerRowId): String = owned(row, SHORT_TITLE)

        override fun primaryLabel(row: BannerRowId): String = owned(row, PRIMARY)

        override fun moreItems(row: BannerRowId): List<String> = listOf(owned(row, GOT_IT))

        private fun owned(row: BannerRowId, text: String): String {
            require(row == BannerRowId.CoachMark) { "this table owns only the CoachMark row, not $row" }
            return text
        }
    }

    object Walkthrough {
        const val BACK = "Back"
        const val NEXT = "Next"
        const val DONE = "Done"
        const val ESC_TO_FINISH = "Esc to finish"
        const val SHOW_TIPS = "Show tips"
        const val REGION = "Quick tips"

        fun indicator(step: Int, total: Int) = "Step $step of $total"

        /** What a screen reader says for a step: the indicator, the title and the sentence, as one string. */
        fun announcement(step: Int, total: Int, title: String, sentence: String) = "${indicator(step, total)}. $title. $sentence"

        const val TITLE_TOOLS = "Tools"
        const val TITLE_ZOOM = "Zoom"
        const val TITLE_SHORTCUTS = "Shortcuts"

        const val TOOLS_GROUPED_TOUCH = "Tools are here. A chevron, or a long press on a group, shows more tools."
        const val TOOLS_GROUPED_MOUSE = "Tools are here. A chevron, or a right-click on a group, shows more tools."
        const val TOOLS_GROUPED_KEYBOARD =
            "Tools are here. Shift+Tab goes back to the tool strip, where the arrow keys move between tools and Down opens a group."
        const val TOOLS_FLAT_POINTER = "All the tools are here, one button each."
        const val TOOLS_FLAT_KEYBOARD =
            "All the tools are here, one button each. Shift+Tab goes back to the tool strip, where the arrow keys move between tools."

        const val ZOOM_TOUCH = "The view buttons fit the drawing and zoom. You can also pinch to zoom."
        const val ZOOM_MOUSE = "The view buttons fit the drawing and zoom. You can also scroll to zoom."
        const val ZOOM_KEYBOARD = "The view buttons fit the drawing and zoom. Shift+Tab goes back to them."

        const val SHORTCUTS_ALL = "Shortcuts are listed in the menu, under Keyboard shortcuts."
    }

    /** The tool strip's affordance texts. */
    object Strip {
        const val MORE_TOOLS = "more tools"
        const val FLYOUT_OPEN_LABEL = "Open more tools"
        const val SHOW_HELP_LABEL = "Show help"

        fun flyoutDescription(group: String) = "$group, $MORE_TOOLS"
    }

    object Style {
        const val ELBOW_NOTE = "Elbow routing isn't supported; drawn as a polyline"
        const val FONT = "Font"
        const val FONT_SIZE = "Font size"
        const val TEXT_ALIGN = "Text align"
        const val VERTICAL_ALIGN = "Vertical align"
        const val ARROW_TYPE = "Arrow type"
        const val START_ARROWHEAD = "Start arrowhead"
        const val END_ARROWHEAD = "End arrowhead"
    }

    /** The hint line of a tool, in the keyboard wording (ux.md section 2) and the touch wording that names no key (UX-124). */
    data class ToolHint(val keyboard: String, val touch: String)

    object Hints {
        private val table: Map<ToolId, ToolHint> = mapOf(
            ToolId.Select to ToolHint("Click to select, drag to move, Shift adds. Tab cycles elements.", "Tap to select, drag to move."),
            ToolId.Hand to ToolHint("Drag to move the view.", "Drag to move the view."),
            ToolId.Rectangle to ToolHint("Drag to draw. Shift = square. From centre: the chip.", "Drag to draw. The chips make it square or draw from the centre."),
            ToolId.Diamond to ToolHint("Drag to draw. Shift = equal sides. From centre: the chip.", "Drag to draw. The chips make the sides equal or draw from the centre."),
            ToolId.Ellipse to ToolHint("Drag to draw. Shift = circle. From centre: the chip.", "Drag to draw. The chips make it a circle or draw from the centre."),
            ToolId.Arrow to ToolHint(
                "Drag or click points. Start or end on a shape to bind. Ctrl (Cmd on macOS) = don't bind.",
                "Drag or tap points. Start or end on a shape to bind. The Don't bind chip turns binding off.",
            ),
            ToolId.Line to ToolHint(
                "Click points; double-click or Enter to finish. Shift = 15 degree steps.",
                "Tap points, then tap Done. The 15 degrees chip snaps the angle.",
            ),
            ToolId.Freedraw to ToolHint("Drag to draw. Pen pressure is kept.", "Drag to draw. Pen pressure is kept."),
            ToolId.Text to ToolHint("Click to type. Esc to finish.", "Tap to type. Tap outside to finish."),
            ToolId.Image to ToolHint("Choose a file, then click to place it.", "Choose a file, then tap to place it."),
            ToolId.Frame to ToolHint("Drag around elements to frame them.", "Drag around elements to frame them."),
            ToolId.Eraser to ToolHint("Drag across elements to erase; tap one on touch. Esc cancels.", "Drag across elements to erase, or tap one."),
            ToolId.Point to ToolHint("Click to place a point. It snaps to endpoints and lines.", "Tap to place a point. It snaps to endpoints and lines."),
            ToolId.Arc to ToolHint(
                "Click the start, click the end, then click a point on the arc.",
                "Tap the start, tap the end, then tap a point on the arc.",
            ),
        )

        fun of(tool: ToolId): ToolHint = table.getValue(tool)

        const val TOOL_LOCK_ON = "Tool lock on."
    }

    /** Labels of the Constrain menu entries (`ConstrainMenuItem`). */
    object Constrain {
        const val COINCIDENT = "Coincident"
        const val HORIZONTAL = "Horizontal"
        const val VERTICAL = "Vertical"
        const val PARALLEL = "Parallel"
        const val PERPENDICULAR = "Perpendicular"
        const val EQUAL = "Equal"
        const val TANGENT = "Tangent"
        const val SYMMETRIC = "Symmetric"
        const val FIXED = "Fixed"
        const val DISTANCE = "Distance"
        const val ANGLE = "Angle"
        const val RADIUS_OR_DIAMETER = "Radius/Diameter"
        const val REFERENCE_TOGGLE = "Reference"
    }

    object EdgePan {
        const val DETAILS = "Edge pan stopped working here. Use two fingers, the Hand tool or the pan arrows."
        const val SUMMARY = "Edge pan stopped."
        const val SHORT = "Edge pan off"
        const val PRIMARY = "Got it"
    }

    /** The `LargeDrawing` banner row (ux.md section 15): what it says and its one button. */
    object LargeScene {
        const val SUMMARY = "Large drawing: effects off."
        const val SHORT = "Large drawing"
        const val PRIMARY = "Turn on"
        const val PRIMARY_NAME = "Re-enable effects"
    }

    /** The web `SingleKeyChip` banner row (UX-108). */
    object SingleKeyChip {
        const val SUMMARY = "Single keys are off."
        const val SHORT = "Keys off"
        const val PRIMARY = "Turn on"
        const val PRIMARY_NAME = "Turn on single-key shortcuts"
        const val GOT_IT = "Got it"
    }

    object FingerDrawing {
        /** The `PenDetected`, `FingerHintOff` and `FingerHintOn` banner rows (ux.md chrome table rows 6, 10 and 11). */
        const val PEN_SUMMARY = "Pen found. Finger off."
        const val PEN_SHORT = "Pen found"
        const val PEN_PRIMARY = "Back on"
        const val PEN_PRIMARY_NAME = "Back on for finger drawing"
        const val HINT_OFF_SUMMARY = "Finger drags move the view."
        const val HINT_OFF_SHORT = "Finger pans"
        const val HINT_OFF_PRIMARY = "Draw"
        const val HINT_OFF_PRIMARY_NAME = "Draw with one finger"
        const val HINT_ON_SUMMARY = "Finger draws. 2 fingers pan."
        const val HINT_ON_SHORT = "Finger draws"
        const val HINT_ON_PRIMARY = "Turn off"
        const val HINT_ON_PRIMARY_NAME = "Turn off finger drawing"
        const val GOT_IT = "Got it"

        const val PEN_DETECTED_DETAILS = "A pen was detected, so Finger drawing is now off. One finger moves the view; the pen draws."
        const val ON_DETAILS = "Finger drawing is on. Drag to draw; use two fingers to move the view."
        const val OFF_DETAILS = "Finger drags move the view. Turn on Finger drawing to draw with touch."
        const val TOOL_HINT_OFF = "Drag with a pen or mouse. With touch, turn on Finger drawing, or use Crosshair."
    }

    object Panning {
        fun settled(zoomPercent: Int, centreX: Int, centreY: Int): String = "Zoom $zoomPercent%, centre $centreX, $centreY"
    }

    object Shortcuts {
        fun constrainMenuRow(label: String): String = "$label (Constrain menu open)"

        /** The recorded WCAG 2.5.7 exemption: a freehand stroke is path-dependent, so its own stroke needs a drag (UX-107). */
        const val FREEDRAW_EXEMPTION =
            "Freedraw strokes need a drag, so the Freedraw tool has no keyboard path. Draw a polyline with the Line tool, or with Crosshair mode and Draw polyline by points."
    }

    /** The non-drag transform, bind-end and marquee panel of Crosshair mode (ux.md section 10, UX-40). */
    object NonDrag {
        const val MOVE_ELEMENT = "Move element"
        const val RESIZE = "Resize"
        const val ROTATE = "Rotate"
        const val BIND_END = "Bind end"
        const val MARQUEE = "Marquee"

        const val STEP = "Step"
        const val STEP_ONE = "1 px"
        const val STEP_TEN = "10 px"
        const val STEP_PERCENT = "1%"

        const val MINIMUM_SIZE = "Minimum size reached"
        const val MAXIMUM_SIZE = "Maximum size reached"
        const val LOCKED = "Locked: unlock it to edit."
        const val NOTHING_SELECTED = "Select an element first."
        const val NOT_ROTATABLE = "This selection cannot be rotated."
        const val NOT_AN_ARROW = "Select one arrow first."
        const val NO_TARGETS = "No shape to bind to."

        const val CANCEL = "Cancel"
        const val APPLY = "Apply"
        const val SELECT = "Select"
        const val PLACE_CORNER = "Place corner"
        const val NUDGE_UP = "Nudge up"
        const val NUDGE_DOWN = "Nudge down"
        const val NUDGE_LEFT = "Nudge left"
        const val NUDGE_RIGHT = "Nudge right"
        const val END_START = "Start"
        const val END_END = "End"
        const val TARGETS = "Shapes near this end"

        fun movePreview(x: String, y: String) = "x $x, y $y"

        fun resizePreview(w0: String, w1: String, h0: String, h1: String) = "width $w0 → $w1, height $h0 → $h1"

        fun rotatePreview(from: String, to: String) = "angle $from° → $to°"

        fun bindPreview(end: String, target: String?) = if (target == null) "Pick a shape to bind the $end to." else "Bind the $end to $target"

        fun candidateLabel(name: String, text: String) = if (text.isEmpty()) name else "$name: $text"

        fun firstCorner(x: Int, y: Int) = "Corner placed at $x, $y. Move the crosshair, then place the opposite corner."

        fun cursorAt(x: Int, y: Int) = "Crosshair at $x, $y. Place the first corner."

        fun regionSummary(count: Int) = when (count) {
            0 -> "No elements in region"
            1 -> "1 element in region"
            else -> "$count elements in region"
        }
    }

    /** What Crosshair drawing says through the live region while a drawing tool is driven by the keyboard (UX-107, ux.md section 10). */
    object KeyboardDrawing {
        const val STARTED = "Crosshair mode. Arrow keys move the crosshair, Shift moves ten units. Enter places a point. Esc cancels."
        const val CANCELLED = "Cancelled. Nothing was added."
        const val ENDED = "Crosshair mode ended."
        const val NOTHING_DRAWN = "Nothing drawn"
        const val CHIP = "Crosshair"
        const val PAD = "Crosshair pad"
        const val PLACE = "Place point"
        const val EXIT = "Exit Crosshair"
        const val CHIP_TAG_NAME = "Start Crosshair mode"

        fun added(name: String): String = "Added $name"

        fun cornerPlaced(x: Int, y: Int): String = "Corner placed at $x, $y. Move the crosshair, then press Enter for the opposite corner."

        fun pointPlaced(count: Int, x: Int, y: Int): String = "Point $count placed at $x, $y."

        fun crosshairAt(x: Int, y: Int): String = "Crosshair at $x, $y"
    }

    /** The restore and delete-backups dialogs, the `Restored` toast, failure messages and the backups size row. */
    object Restore {
        const val ACTION = "Restore original (v1) sheet"
        const val TITLE = "Restore the original sketch?"
        const val BODY =
            "Everything you changed in the new editor since the upgrade will be discarded. A copy of the new-editor version is kept in backup/."
        const val CONFIRM = "Restore original"
        const val CANCEL = "Cancel"
        const val RESTORED_KEY = "Restored"
        const val RESTORED_SUMMARY = "Restored. Copy kept."
        const val RESTORED_ACTION = "Copy path"
        const val RESTORED_DETAILS = "Restored the original sketch. The new-editor version is kept in backup/."
        const val DELETE_ACTION = "Delete backups"
        const val DELETE_TITLE = "Delete backups?"
        const val DELETE_BODY =
            "The original copies of upgraded sketches in this graph are deleted. Restore original is not available for them afterwards."
        const val BUILD_CAPPED = "Not available in this build yet"
        const val NOT_CONVERTED = "This sketch is already the original version. Nothing was changed."
        const val BACKUP_MISSING = "The original copy of this sketch was not found. Nothing was changed."
        const val DEFERRED = "A sync is running, or changes from another device are still being merged. Nothing was changed; try again in a moment."
        const val FAILED = "Could not restore the original sketch. Your sketch was not lost."
        const val NONE = "none"

        fun backupsRow(description: String): String = "Backups: $description"

        fun fileCount(files: Int, size: String): String = "$files ${if (files == 1) "file" else "files"}, $size"

        fun megabytes(tenths: String): String = "$tenths MB"

        fun kilobytes(tenths: String): String = "$tenths KB"
    }

    /** Chrome shared by the banner row, the toast lane, the chip row and the top bar (Story 3.1.4). */
    object Chrome {
        /** Toast placeholders and the filler the worst-case fit check expands them with (never shown to a user). */
        const val PLACEHOLDER_NAME = "{name}"
        const val PLACEHOLDER_COUNT = "{n:3}"
        const val PLACEHOLDER_NAME_FILL = "x"
        const val PLACEHOLDER_COUNT_FILL = "000"

        const val MORE = "More"
        const val DISMISS = "Dismiss"
        const val DETAILS = "Details"
        const val TOOL_LOCK_CHIP = "Tool lock"
        const val MORE_OPTIONS = "More options"
        const val SCROLL_CHIPS = "More actions"
        const val UPGRADE_SHEET_NOT_NOW = "Not now"
        const val SEVERITY_BLOCKING = "Error"
        const val SEVERITY_STATE = "Notice"
        const val SEVERITY_WARNING = "Warning"
        const val SEVERITY_INFO = "Information"
        const val SEVERITY_ONBOARDING = "Tip"

        fun moreNotices(count: Int): String = "+$count more"

        fun moreFor(name: String): String = "$MORE: $name"
    }

    /** The top bar (Story 3.1.4d, UX-80): the Finger drawing chip at both tiers and the save status. */
    object TopBar {
        const val BACK = "Back"
        const val HELP = "Help and search"
        const val FINGER_ON = "Finger: On"
        const val FINGER_OFF = "Finger: Off"
        const val FINGER_ON_SHORT = "On"
        const val FINGER_OFF_SHORT = "Off"
        const val FINGER_SEMANTICS = "Finger drawing"
        const val FINGER_TOOLTIP_ON = "Finger drawing: On"
        const val FINGER_TOOLTIP_OFF = "Finger drawing: Off"
        const val STATE_ON = "on"
        const val STATE_OFF = "off"
        const val SAVED = "Saved"
        const val SAVING = "Saving"
        const val UNSAVED = "Unsaved"
        const val FAILED = "Failed"
        const val SAVED_FULL = "Saved"
        const val SAVING_FULL = "Saving"
        const val UNSAVED_FULL = "Unsaved changes"
        const val FAILED_FULL = "Save failed"
    }

    /** The compact-height Selection menu (Story 3.1.4d, UX-127). */
    object SelectionMenu {
        const val BUTTON = "Selection"
        const val DUPLICATE = "Duplicate"
        const val GROUP = "Group"
        const val UNGROUP = "Ungroup"
        const val ALIGN = "Align"
        const val ORDER = "Order"
        const val TRANSFORM = "Transform"
        const val LOCK = "Lock"
        const val UNLOCK = "Unlock"
        const val BIND_END = "Bind end..."

        fun semantics(count: Int): String = "$BUTTON, $count ${if (count == 1) "element" else "elements"} selected"
    }

    /** The solver runner's status text and its failure toast (Story 4.1.4, UX-35, UX-36). */
    object Solve {
        const val SOLVING = "Solving..."
        const val CANCEL = "Cancel"
        const val CANCEL_NAME = "Cancel solving"
        const val CANT_SATISFY_KEY = "CantSatisfy"
        const val CANT_SATISFY_SUMMARY = "Can't satisfy constraints here."
        const val CANT_SATISFY_SHORT = "Can't solve"
        const val CANT_SATISFY_DETAILS = "The constraints disagree, so nothing was moved. Undo the last change or remove a constraint."
    }

    /** The local usage counters of Settings > Sketches (Task 6.2.3b; metric 8 and the flag-exit criteria). */
    object Signals {
        const val HEADING = "Usage counts on this device"
        const val LOCAL_ONLY = "Kept on this device only. Never sent anywhere."
        const val SELF_REPORT = "Diagrams drawn elsewhere are not seen here, so the share of your new diagrams is your own count."
        const val NEW_PROJECTS_ONLY = "New sketch projects only: new diagrams in existing projects are classic and are not counted."

        fun authored(count: Long) = "Sketches drawn in the new editor: $count"

        fun imported(count: Long) = "Excalidraw files imported: $count"

        fun sessions(count: Long) = "New-editor sessions: $count"

        fun crashes(count: Long) = "Crashes: $count"

        fun solves(count: Long) = "Constraint solves: $count"

        fun noConvergence(count: Long) = "Solves that did not converge: $count"

        fun conflicts(count: Long) = "Save conflicts: $count"

        const val READINGS_NOTE = "Readings against the exit thresholds. They advise your decision; nothing changes by itself."
        const val STOP_SHIP = "Stop-ship threshold reached: turn the new editor off and report it."

        fun crashReading(reading: String) = "Crash rate: $reading"

        fun noConvergenceReading(reading: String) = "Solves that did not converge: $reading"

        const val READING_MET = "within the flip threshold"
        const val READING_NOT_MET = "above the flip threshold"
        const val READING_INSUFFICIENT = "not enough data yet"
    }

    /** The scene hard cap and the constraint cap (Story 2.2.4, UX-73) and the low-memory toast. */
    /** The prompt shown when the user leaves a sheet whose latest edits are not on disk (UX-72). */
    /** The panel's tab names (ux.md section 17): the tab list is a group of selectable tabs, each named in words. */
    /** The overflow entries that open the side panels and switch the sheet type; a phone has no other way to reach them. */
    object PanelMenu {
        const val MAKE_TECHNICAL = "Make this a technical sheet"
        const val MAKE_DIAGRAM = "Make this a diagram sheet"
    }

    object PanelTabs {
        const val LIST = "Panels"
        const val STYLE = "Style"
        const val LAYERS = "Layers"
        const val CONSTRAINTS = "Constraints"
        const val ELEMENTS = "Elements"
        const val LIBRARY = "Library"
        const val TECHNICAL = "Technical"
    }

    object Leave {
        const val TITLE = "Changes not saved"
        const val BODY = "Your latest changes are not saved yet. Your work is kept until you choose."
        const val RETRY = "Retry save"
        const val EXPORT_COPY = "Export copy"
        const val DISCARD = "Discard"
    }

    object Capacity {
        const val DELETE_SOME = "Delete some"
        const val SPLIT = "Split into a new sketch"
        const val OK = "OK"
        const val CONSTRAINT_LIMIT_KEY = "ConstraintLimit"
        const val CONSTRAINT_LIMIT_SUMMARY = "Constraint limit reached."
        const val CONSTRAINT_LIMIT_SHORT = "Limit"
        const val CONSTRAINT_LIMIT_REVIEW = "Review"
        const val LOW_MEMORY_KEY = "LowMemory"
        const val LOW_MEMORY_SUMMARY = "Low memory."
        const val LOW_MEMORY_SHORT = "Low memory"
        const val LOW_MEMORY_ACTION = "Save now"
        const val LOW_MEMORY_DETAILS = "Save and reopen the sketch."
        const val SPLIT_UNDONE_ACTION = "Delete the new sketch"
        const val SPLIT_NEEDS_SELECTION = "Select the elements to move first"
        const val SPLIT_UNDONE_KEY = "SplitUndone"

        fun sheetLabel(position: Int): String = "Sheet ${position + 1}"

        fun hardCap(maxElements: Int): String = "This sketch has ${grouped(maxElements)} elements, the maximum."

        fun constraintLimitDetails(maxConstraints: Int): String = "$maxConstraints constraints is the limit for one sketch."

        fun splitUndoneNotice(name: String): String = "$SPLIT_UNDONE_ACTION '$name'"

        private fun grouped(value: Int): String {
            val digits = value.toString()
            return digits.reversed().chunked(GROUP_DIGITS).joinToString(",").reversed()
        }

        private const val GROUP_DIGITS = 3
    }

    /** The technical-data panel of the v2 editor (Story 4.2.5): named dimensions and zones of a converted sheet. */
    object TechnicalData {
        const val HEADING = "Dimensions and zones"
        const val DIMENSIONS_HEADING = "Named dimensions"
        const val NO_DIMENSIONS = "No named dimensions in this project"
        const val SUM_VIOLATED = "Zones do not add up to the total"
        const val NOT_REPRESENTABLE_NOTE = "not a whole sixteenth of an inch"

        fun lockedDimension(key: String, value: String) = "$key, $value, locked"

        fun dimensionSet(key: String, value: String) = "Set $key to $value"
    }

    /** Screen-reader text of the canvas elements, the skip link and the focus ring (Story 3.6.2, UX-44, UX-45, UX-117). */
    object Semantics {
        const val CANVAS = "Sketch canvas"
        const val SKIP_CANVAS_ELEMENTS = "Skip canvas elements"
        const val EMPTY_SHEET = "Empty sheet"
        const val LOCKED = "locked"
        const val POINTS_NOUN = "points"
        const val ELEMENTS_NOUN = "elements"
        const val HORIZONTAL = "horizontal"
        const val VERTICAL = "vertical"

        fun withDetail(name: String, detail: String) = "$name, $detail"

        fun sized(name: String, width: Long, height: Long) = "$name, $width by $height"

        fun radius(name: String, radius: Long) = "$name, radius $radius"

        fun lineLength(name: String, orientation: String, length: Long) = "$name, $orientation, length $length"

        fun polyline(name: String, points: Int) = "$name, $points $POINTS_NOUN"

        fun quoted(name: String, text: String) = "$name, \"$text\""

        fun container(name: String, text: String) = "$name, labelled \"$text\""

        fun frame(name: String, members: Int) = "$name, $members $ELEMENTS_NOUN"

        fun wall(name: String, length: Long, thickness: Long) = "$name, length $length, thickness $thickness"

        fun opening(name: String, width: Long) = "$name, width $width"

        fun pointAt(name: String, x: Long, y: Long) = "$name, at $x, $y"

        fun arrowBetween(from: String, to: String) = "Arrow from $from to $to"

        fun arrowFrom(from: String) = "Arrow from $from"

        fun arrowTo(to: String) = "Arrow to $to"

        fun arrowNamed(name: String, arrow: String) = "$name, ${arrow.replaceFirstChar { it.lowercase() }}"

        fun lockedSuffix(label: String) = "$label, $LOCKED"

        /** Spoken once, with the nodes, when the sheet has more elements than the semantics tree may hold (UX-44). */
        fun capStatement(shown: Int, total: Int) =
            "Showing ${grouped(shown)} of ${grouped(total)} elements for screen reader; use Find to reach others"

        private fun grouped(value: Int): String {
            val digits = value.toString()
            return digits.reversed().chunked(GROUP_DIGITS).joinToString(",").reversed()
        }

        private const val GROUP_DIGITS = 3
    }
}
