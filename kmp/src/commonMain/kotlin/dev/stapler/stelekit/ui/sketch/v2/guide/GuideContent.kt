// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.guide

import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.TabStop
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchGuideStrings as S

/** The feature names the editor reports to [TourController.onFeatureFirstUse]; a tour lists the ones that start it. */
object GuideFeatures {
    const val CONSTRAINTS = "feature.constraints"
    const val TECHNICAL_TAB = "feature.technical-tab"
    const val POINT_DRAG = "feature.point-drag"
    const val TECHNICAL_SHAPE = "feature.technical-shape"

    /** The constraint-only tools: using one is the first use of constraints. */
    fun ofTool(id: ToolId): String? = when (id) {
        ToolId.Point, ToolId.Arc -> CONSTRAINTS
        else -> null
    }
}

/** A Help topic: [keywords] feed search, [tourId] (when set) adds Replay. */
data class HelpTopic(val id: String, val title: String, val body: String, val keywords: List<String> = emptyList(), val tourId: String? = null)

/** The tours and Help topics of the editor. */
object GuideContent {
    const val ORIENTATION = "orientation"
    const val CONSTRAINTS = "constraints"
    const val TECHNICAL = "technical"
    const val POINT_DRAG = "point-drag"
    const val DIMENSIONS = "dimensions"

    private val orientation = Tour(
        ORIENTATION, S.Orientation.TITLE,
        listOf(
            TourStep("tools", TabStop.ToolStrip, S.Orientation.TOOLS_TITLE, S.Orientation.TOOLS_BODY),
            TourStep("canvas", TabStop.Canvas, S.Orientation.CANVAS_TITLE, S.Orientation.CANVAS_BODY),
            TourStep("panels", TabStop.SidePane, S.Orientation.PANEL_TITLE, S.Orientation.PANEL_BODY),
            TourStep("undo", TabStop.ToolStrip, S.Orientation.UNDO_TITLE, S.Orientation.UNDO_BODY),
            TourStep("menu", TabStop.TopBar, S.Orientation.OVERFLOW_TITLE, S.Orientation.OVERFLOW_BODY),
        ),
    )

    private val constraints = Tour(
        CONSTRAINTS, S.Constraints.TITLE,
        listOf(
            TourStep("what", null, S.Constraints.WHAT_TITLE, S.Constraints.WHAT_BODY),
            TourStep("pick", TabStop.Canvas, S.Constraints.PICK_TITLE, S.Constraints.PICK_BODY),
            TourStep("panel", TabStop.SidePane, S.Constraints.PANEL_TITLE, S.Constraints.PANEL_BODY),
            TourStep("dimension", TabStop.Canvas, S.Constraints.DIMENSION_TITLE, S.Constraints.DIMENSION_BODY),
            TourStep("auto", TabStop.TopBar, S.Constraints.AUTO_TITLE, S.Constraints.AUTO_BODY),
        ),
        setOf(GuideFeatures.CONSTRAINTS),
    )

    private val technical = Tour(
        TECHNICAL, S.TechnicalData.TITLE,
        listOf(
            TourStep("tab", TabStop.SidePane, S.TechnicalData.TAB_TITLE, S.TechnicalData.TAB_BODY),
            TourStep("edit", TabStop.SidePane, S.TechnicalData.EDIT_TITLE, S.TechnicalData.EDIT_BODY),
        ),
        setOf(GuideFeatures.TECHNICAL_TAB),
    )

    private val pointDrag = Tour(
        POINT_DRAG, S.PointDrag.TITLE,
        listOf(
            TourStep("grab", TabStop.Canvas, S.PointDrag.GRAB_TITLE, S.PointDrag.GRAB_BODY),
            TourStep("snap", TabStop.Canvas, S.PointDrag.SNAP_TITLE, S.PointDrag.SNAP_BODY),
        ),
        setOf(GuideFeatures.POINT_DRAG),
    )

    private val dimensions = Tour(
        DIMENSIONS, S.Dimensions.TITLE,
        listOf(
            TourStep("pill", TabStop.Canvas, S.Dimensions.PILL_TITLE, S.Dimensions.PILL_BODY),
            TourStep("chip", TabStop.Canvas, S.Dimensions.CHIP_TITLE, S.Dimensions.CHIP_BODY),
            TourStep("units", TabStop.Canvas, S.Dimensions.UNITS_TITLE, S.Dimensions.UNITS_BODY),
        ),
        setOf(GuideFeatures.TECHNICAL_SHAPE),
    )

    val tours: List<Tour> = listOf(orientation, constraints, technical, pointDrag, dimensions)

    fun registry(): TourRegistry = TourRegistry(tours)

    val helpTopics: List<HelpTopic> = listOf(
        HelpTopic(ORIENTATION, S.Help.ORIENTATION, S.Help.ORIENTATION_BODY, listOf("tour", "welcome", "start", "interface", "orient"), ORIENTATION),
        HelpTopic("tools", S.Help.TOOLS, S.Help.TOOLS_BODY, listOf("tool", "draw", "shape", "strip")),
        HelpTopic(CONSTRAINTS, S.Help.CONSTRAINTS, S.Help.CONSTRAINTS_BODY, listOf("constrain", "rule", "parallel", "horizontal", "vertical", "dimension", "conflict", "auto"), CONSTRAINTS),
        HelpTopic(TECHNICAL, S.Help.TECHNICAL, S.Help.TECHNICAL_BODY, listOf("technical", "inches", "dimension", "zone", "grid", "size"), TECHNICAL),
        HelpTopic(DIMENSIONS, S.Help.DIMENSIONS, S.Help.DIMENSIONS_BODY, listOf("dimension", "width", "height", "size", "pill", "type", "measure", "inches", "length", "diameter", "technical"), DIMENSIONS),
        HelpTopic("dimensions.all", S.Help.DIMENSIONS_ALL, S.Help.DIMENSIONS_ALL_BODY, listOf("dimension", "elevation", "annotation", "persistent", "all", "show", "hide", "extension", "line")),
        HelpTopic("text-size", S.Help.TEXT_SIZE, S.Help.TEXT_SIZE_BODY, listOf("text", "font", "size", "resize", "autosize", "wrap", "scale", "a+", "a-", "bigger", "smaller")),
        HelpTopic(POINT_DRAG, S.Help.POINT_DRAG, S.Help.POINT_DRAG_BODY, listOf("point", "vertex", "drag", "snap", "corner", "endpoint"), POINT_DRAG),
        HelpTopic("snapping", S.Help.SNAPPING, S.Help.SNAPPING_BODY, listOf("snap", "guide", "align", "magnet", "grid", "ctrl", "hold", "resize", "move", "pause", "bypass")),
        HelpTopic("gestures", S.Help.GESTURES, S.Help.GESTURES_BODY, listOf("touch", "pinch", "zoom", "pan", "tap", "drag", "loupe", "magnifier", "finger")),
        HelpTopic("keyboard", S.Help.KEYBOARD, S.Help.KEYBOARD_BODY, listOf("shortcut", "key", "ctrl", "cmd", "undo")),
    ) + ToolId.entries.map { id ->
        val (name, what) = S.tool(id)
        HelpTopic("tool.${id.name.lowercase()}", name, what, listOf("tool", name.lowercase()))
    }
}
