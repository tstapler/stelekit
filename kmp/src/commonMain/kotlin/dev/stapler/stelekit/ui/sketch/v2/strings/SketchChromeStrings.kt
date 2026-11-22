// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

import dev.stapler.stelekit.sketch.v2.tools.ToolId

/**
 * One line of "what this does" for every icon-only control, shown with the control's name and shortcut by a long press, by
 * keyboard focus and by hover. Icons alone are not enough for a Point, an Arc or Constrain, so the popup says it in words.
 */
object SketchChromeStrings {
    fun toolHint(tool: ToolId): String = when (tool) {
        ToolId.Select -> "Tap a shape to select it, drag to move it, drag a handle to resize it."
        ToolId.Hand -> "Drag the canvas to move the view."
        ToolId.Rectangle -> "Drag to draw a rectangle."
        ToolId.Diamond -> "Drag to draw a diamond."
        ToolId.Ellipse -> "Drag to draw an ellipse or a circle."
        ToolId.Arrow -> "Drag from one place to another; end it on a shape to attach it."
        ToolId.Line -> "Tap points to draw a line; double tap to finish."
        ToolId.Freedraw -> "Draw freehand with a finger or a pen."
        ToolId.Text -> "Tap the canvas, then type."
        ToolId.Image -> "Choose a picture to place on the sketch."
        ToolId.Frame -> "Drag to draw a frame that groups what is inside it."
        ToolId.Eraser -> "Drag over shapes to delete them."
        ToolId.Point -> "Tap to place a point that constraints can attach to."
        ToolId.Arc -> "Drag to draw a circular arc for constraints."
    }

    const val ZOOM_TO_SELECTION = "Zoom to selection"
    const val UNDO = "Undo"
    const val REDO = "Redo"
    const val DELETE = "Delete"
    const val DELETE_UNAVAILABLE = "Delete, unavailable: nothing selected"
    const val CONSTRAIN = "Constrain (K)"
    const val CONSTRAIN_UNAVAILABLE = "Constrain, unavailable: Select an element to constrain"
    const val TOOL_LOCK_ON = "Tool lock on"
    const val TOOL_LOCK_OFF = "Tool lock off"
    const val ZOOM_OUT = "Zoom out"
    const val ZOOM_IN = "Zoom in"
    const val PAN_ARROWS = "Pan arrows"

    fun zoom(percent: Int) = "Zoom $percent%"

    const val UNDO_HINT = "Take back the last change."
    const val REDO_HINT = "Bring back the change you took back."
    const val DELETE_HINT = "Delete the selected shapes."
    const val CONSTRAIN_HINT = "Lock a relationship between two parts, such as equal length or parallel."
    const val TOOL_LOCK_HINT = "Keep the same tool after each shape instead of returning to Select."
    const val FLYOUT_HINT = "Tap to pick another tool of this kind."

    const val BACK_HINT = "Return to the project."
    const val HELP_HINT = "Open help, tours and search for tools."
    const val FINGER_HINT = "When on, one finger draws; when off, one finger moves the view."
    const val SAVE_HINT = "Shows whether your latest changes are saved."
    const val OVERFLOW_HINT = "Panels, sheet type, view options, import, export and help."

    const val ZOOM_OUT_HINT = "Zoom out."
    const val ZOOM_IN_HINT = "Zoom in."
    const val ZOOM_LEVEL_HINT = "Tap to return to 100%."
    const val GRID_HINT = "Show a grid and place points on it."
    const val SNAP_HINT = "Snap to corners, ends and centres of other shapes."

    const val MENU_VIEW = "View"
    const val MENU_PANELS = "Panels"
    const val MENU_SHEET = "Sheet"
    const val MENU_TOOLS = "Tools"
    const val MENU_HELP = "Help"
    const val MENU_FILE = "Import and export"

    fun moreTool(label: String, shortcut: String) = "$label ($shortcut)"
}
