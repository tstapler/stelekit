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
}
