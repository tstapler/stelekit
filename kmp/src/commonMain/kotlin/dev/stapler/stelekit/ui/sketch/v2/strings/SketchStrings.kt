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
    }

    object FingerDrawing {
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

    /** What Crosshair drawing says through the live region while a drawing tool is driven by the keyboard (UX-107, ux.md section 10). */
    object KeyboardDrawing {
        const val STARTED = "Crosshair mode. Arrow keys move the crosshair, Shift moves ten units. Enter places a point. Esc cancels."
        const val CANCELLED = "Cancelled. Nothing was added."
        const val ENDED = "Crosshair mode ended."
        const val NOTHING_DRAWN = "Nothing drawn"

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
            "The original copies of upgraded sketches in this project are deleted. Restore original is not available for them afterwards."
        const val BUILD_CAPPED = "Not available in this build yet"
        const val NOT_CONVERTED = "This sketch is already the original version. Nothing was changed."
        const val BACKUP_MISSING = "The original copy of this sketch was not found. Nothing was changed."
        const val DEFERRED = "A sync is running. Nothing was changed; try again in a moment."
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

    /** The scene hard cap and the constraint cap (Story 2.2.4, UX-73) and the low-memory toast. */
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
}
