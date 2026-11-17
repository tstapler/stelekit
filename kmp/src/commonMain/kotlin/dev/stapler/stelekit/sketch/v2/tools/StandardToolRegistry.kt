// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider

/** The registry every editor host builds: every tool of the frozen inventory in its Idle state. */
object StandardToolRegistry {
    fun create(metrics: TextMetricsProvider, limits: SceneLimits = SceneLimits.Default, touchPresent: Boolean = false): ToolRegistry =
        ToolRegistry.Empty
            .with(ToolId.Select) { SelectTool(touchPresent = touchPresent) }
            .with(ToolId.Rectangle) { RectangleTool() }
            .with(ToolId.Diamond) { DiamondTool() }
            .with(ToolId.Ellipse) { EllipseTool() }
            .with(ToolId.Arrow) { ArrowTool() }
            .with(ToolId.Line) { LineTool() }
            .with(ToolId.Freedraw) { FreedrawTool(limits) }
            .with(ToolId.Text) { TextTool(metrics, limits) }
            .with(ToolId.Image) { ImageTool() }
            .with(ToolId.Eraser) { EraserTool() }
            .with(ToolId.Frame) { FrameTool() }
            .with(ToolId.Hand) { HandTool() }
            .with(ToolId.Point) { PointTool() }
            .with(ToolId.Arc) { ArcTool() }
}
