// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

import dev.stapler.stelekit.sketch.v2.tools.ToolId

/** The words of the tours, Help and search (part of the string table, so the hardcoded-string audit does not scan it). */
object SketchGuideStrings {
    const val BACK = "Back"
    const val NEXT = "Next"
    const val DONE = "Done"
    const val SKIP = "Skip tour"
    const val DONT_SHOW = "Don't show tips"

    fun indicator(step: Int, total: Int) = "Step $step of $total"

    /** The orientation tour: where things are. Started from Help, never on its own (the first-run walkthrough covers that). */
    object Orientation {
        const val TITLE = "Find your way around"
        const val TOOLS_TITLE = "Tools"
        const val TOOLS_BODY = "Pick a tool here, then draw on the canvas. Select moves and resizes; the rest draw. Tools that don't fit are under More."
        const val CANVAS_TITLE = "Canvas"
        const val CANVAS_BODY = "Draw here. Pinch to zoom; pan with the Hand tool or the pan pad."
        const val PANEL_TITLE = "Panels"
        const val PANEL_BODY = "Style, Layers, Constraints and the rest open here. Select a shape and its style and size fields appear."
        const val UNDO_TITLE = "Undo and redo"
        const val UNDO_BODY = "Every change is one undo step, so you can always step back."
        const val OVERFLOW_TITLE = "Menu and help"
        const val OVERFLOW_BODY = "The top bar holds the menu: import, export, Go to element, shortcuts and Help. Search finds any tool or action by name."
    }

    /** The constraints tour; it starts the first time a constraint tool or the Constraints panel is used. */
    object Constraints {
        const val TITLE = "How constraints work"
        const val WHAT_TITLE = "What a constraint is"
        const val WHAT_BODY = "A constraint is a rule the sketch keeps true: two lines stay parallel, a line stays 4 in long, a point stays on a circle. Change one thing and the rest follows."
        const val PICK_TITLE = "Pick the parts"
        const val PICK_BODY = "Select a line, circle or point, then choose Constrain and pick the rule. A rule that needs a second part asks you to tap it. Where several parts overlap, a list lets you choose."
        const val PANEL_TITLE = "The Constraints panel"
        const val PANEL_BODY = "Every rule is listed here with its state: OK, Redundant or Conflict. Delete one to free the shape again."
        const val DIMENSION_TITLE = "Dimensions"
        const val DIMENSION_BODY = "Distance, Angle and Radius are rules with a number. Tap the number on the canvas to change it and the shape resizes to match."
        const val AUTO_TITLE = "Auto-constrain"
        const val AUTO_BODY = "While you draw, obvious rules such as horizontal or touching are added for you, with an Undo. Turn it off in the menu, or tap Free draw to skip it once."
    }

    /** The Technical data tab. */
    object TechnicalData {
        const val TITLE = "Named dimensions"
        const val TAB_TITLE = "The Technical tab"
        const val TAB_BODY = "Name the sizes that matter, like width or height, and zones such as a drawer or a cut-out. They are saved with the sketch."
        const val EDIT_TITLE = "Change a size"
        const val EDIT_BODY = "Edit a value and the drawing follows. Sizes are in inches and sixteenths."
    }

    /** Typing a size on the canvas of a technical sheet. */
    object Dimensions {
        const val TITLE = "Type a size on the canvas"
        const val PILL_TITLE = "Tap a size to type it"
        const val PILL_BODY = "Select a shape on a technical sheet and its sizes appear beside it. Tap one, type a length such as 12 3/16\", then tap Next or Done. Next moves from Width to Height; tap outside to cancel."
        const val CHIP_TITLE = "Show or hide the sizes"
        const val CHIP_BODY = "The Dimensions chip at the bottom turns the size pills on or off. Constraints and Technical data open their panels from the same row."
    }

    /** Dragging one point of a line. */
    object PointDrag {
        const val TITLE = "Move one point"
        const val GRAB_TITLE = "Grab a point"
        const val GRAB_BODY = "With a line selected, press close to one of its ends or corners and drag. Only that point moves; the rest of the line stays put."
        const val SNAP_TITLE = "It snaps"
        const val SNAP_BODY = "A point near another shape's corner, end or centre snaps to it. Hold Shift to snap the angle in 15 degree steps."
    }

    /** One help topic: the full text behind a tour or a tool. */
    object Help {
        const val TITLE = "Help"
        const val CLOSE = "Close"
        const val BACK = "All topics"
        const val REPLAY = "Replay tour"
        const val TIPS_SWITCH = "Show tips for new features"
        const val NO_MATCH = "No topic matches that."

        const val TOOLS = "Tools"
        const val CONSTRAINTS = "Constraints"
        const val TECHNICAL = "Technical sheets"
        const val POINT_DRAG = "Moving one point"
        const val DIMENSIONS = "Typing sizes on the canvas"
        const val GESTURES = "Touch gestures"
        const val SNAPPING = "Snapping and guides"
        const val KEYBOARD = "Keyboard"
        const val ORIENTATION = "Finding your way around"

        const val TOOLS_BODY = "Select moves and resizes what you draw. Rectangle, Diamond, Ellipse, Arrow, Line and Freedraw make shapes. Text adds words, Image adds a picture, Frame groups shapes, Eraser removes them. Hand pans the view. On a technical sheet, Point and Arc add exact geometry that constraints can hold."
        const val CONSTRAINTS_BODY = "A constraint is a rule the sketch keeps true. Select a line, circle or point and choose Constrain to add one: Coincident, Horizontal, Vertical, Parallel, Perpendicular, Equal, Tangent, Symmetric, Fixed, and the numeric ones Distance, Angle and Radius. The Constraints panel lists every rule with its state. Redundant means a rule repeats another; Conflict means two rules cannot both be true, and Show conflicts points at them. Auto-constrain adds the obvious rules as you draw."
        const val TECHNICAL_BODY = "Open the three-dot menu and choose Make this a technical sheet to get inch dimensions, a sixteenth-inch grid, the Point and Arc tools and the Constrain tools. The Technical tab (three-dot menu, Technical data) holds named dimensions and zones; edit a value and the drawing follows. Size fields for the selected shape are in the Style tab."
        const val DIMENSIONS_BODY = "On a technical sheet, select one shape and its sizes show as pills beside it: Width and Height for a rectangle, Diameter for a circle, Length for a line. Tap a pill, type a length such as 12 3/16\" or 3' 4\", and tap Next or Done; Next on Width moves on to Height. The cancel button, or a tap on the canvas, ends the edit, and a length that cannot be read keeps the old size and says why. With a keyboard, Enter applies, Shift+Tab goes back and Esc cancels. The Dimensions chip in the bottom row shows or hides the pills."
        const val POINT_DRAG_BODY = "Select a line, then press close to one of its points and drag; only that point moves. Pressing within about a finger's width of the point is enough. The point snaps to other shapes' corners, ends and centres; hold Shift to snap the angle to 15 degree steps. Release to finish; it is one undo step."
        const val SNAPPING_BODY = "Moving or resizing a shape snaps its edges and centre to other shapes' edges and centres (and to the grid when it is on). A thin guide line shows what it snapped to, and touch gives a light tick. Rotated shapes snap too when turned by a quarter turn. To skip snapping for one drag: hold Ctrl with a mouse, or on touch rest a second finger on the Snap button while you drag. The Snap button turns it off for good."
        const val GESTURES_BODY = "Tap selects. Drag moves. Drag a handle to resize or rotate. Pinch zooms; the Hand tool or the pan pad pans. With a line selected, press a corner or end and drag to move just that point. Double tap enters a group. While a finger drags a shape, handle or point, a magnifier bubble shows the exact spot beside it."
        const val KEYBOARD_BODY = "Ctrl+/ (Cmd+/ on a Mac) opens Help and search for tools and actions. Ctrl or Cmd+Z undoes; add Shift to redo. Delete removes the selection. Esc cancels a gesture or deselects. F6 moves between regions; Tab moves within one. The menu entry Keyboard shortcuts lists every key."
        const val ORIENTATION_BODY = "The tool strip is where you pick what to draw. The canvas is the drawing. Panels hold style, layers and constraints. Undo and redo are always at hand, and the menu in the top bar has import, export and Help."
    }

    /** Search. */
    object Search {
        const val TITLE = "Search tools and actions"
        const val FIELD = "Search"
        const val NO_MATCH = "Nothing matches that."
        const val KIND_TOOL = "Tool"
        const val KIND_ACTION = "Action"
        const val KIND_HELP = "Help"
        const val OPEN_HELP = "Help and search"
        const val OPEN_SEARCH = "Search"
        const val UNDO = "Undo"
        const val REDO = "Redo"
        const val DELETE = "Delete selection"
        const val CONSTRAIN = "Constrain"
        const val SHORTCUTS = "Keyboard shortcuts"
        const val GO_TO = "Go to element"
        const val FIT = "Fit drawing to the screen"
        const val GRID = "Toggle grid"
        const val SNAP = "Toggle snap"
        const val EXPORT = "Export"
        const val IMPORT = "Import"
    }

    /** The short text of each tool in Help and search: its label and what it does. */
    fun tool(id: ToolId): Pair<String, String> = when (id) {
        ToolId.Select -> "Select" to "Pick, move and resize shapes"
        ToolId.Rectangle -> "Rectangle" to "Draw a rectangle or square"
        ToolId.Diamond -> "Diamond" to "Draw a diamond"
        ToolId.Ellipse -> "Ellipse" to "Draw an ellipse or circle"
        ToolId.Arrow -> "Arrow" to "Draw an arrow, optionally attached to shapes"
        ToolId.Line -> "Line" to "Draw a line or polyline"
        ToolId.Freedraw -> "Freedraw" to "Draw freehand"
        ToolId.Text -> "Text" to "Add text"
        ToolId.Image -> "Image" to "Add a picture"
        ToolId.Eraser -> "Eraser" to "Erase shapes you drag over"
        ToolId.Frame -> "Frame" to "Group shapes in a frame"
        ToolId.Hand -> "Hand" to "Pan the view"
        ToolId.Point -> "Point" to "Add an exact point for constraints"
        ToolId.Arc -> "Arc" to "Add a circular arc for constraints"
    }
}
