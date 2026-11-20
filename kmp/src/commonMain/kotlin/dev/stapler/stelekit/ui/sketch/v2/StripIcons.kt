// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.tools.ToolId

/**
 * What a strip button shows. Drawn, not typed: the old text glyphs made Select and Arrow two near-identical diagonal arrows and
 * rendered as emoji or tofu on some fonts. Each icon is drawn on a 24 x 24 grid and has its own silhouette.
 */
internal enum class StripIcon {
    Select, Hand, Rectangle, Diamond, Ellipse, Arrow, Line, Freedraw, Text, Image, Frame, Eraser, Point, Arc,
    Undo, Redo, Delete, Constrain, LockClosed, LockOpen, More,
    ;

    companion object {
        fun of(tool: ToolId): StripIcon = when (tool) {
            ToolId.Select -> Select
            ToolId.Hand -> Hand
            ToolId.Rectangle -> Rectangle
            ToolId.Diamond -> Diamond
            ToolId.Ellipse -> Ellipse
            ToolId.Arrow -> Arrow
            ToolId.Line -> Line
            ToolId.Freedraw -> Freedraw
            ToolId.Text -> Text
            ToolId.Image -> Image
            ToolId.Frame -> Frame
            ToolId.Eraser -> Eraser
            ToolId.Point -> Point
            ToolId.Arc -> Arc
        }
    }
}

private const val GRID = 24f
private const val ICON_DP = 24
private const val STROKE = 2f

@Composable
internal fun StripIconView(icon: StripIcon, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(ICON_DP.dp)) { drawIcon(icon, color, size.width / GRID) }
}

private fun DrawScope.drawIcon(icon: StripIcon, color: Color, u: Float) {
    val line = Stroke(STROKE * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun poly(vararg pts: Pair<Float, Float>, close: Boolean = false, fill: Boolean = false) {
        val path = Path().apply {
            moveTo(pts[0].first * u, pts[0].second * u)
            for (i in 1 until pts.size) lineTo(pts[i].first * u, pts[i].second * u)
            if (close) close()
        }
        drawPath(path, color, style = if (fill) Fill else line)
    }
    fun seg(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), STROKE * u, StrokeCap.Round)
    when (icon) {
        StripIcon.Select -> poly(6f to 3f, 6f to 19f, 10.5f to 15f, 14f to 21f, 16.5f to 19.8f, 13f to 14f, 19f to 14f, close = true, fill = true)
        StripIcon.Hand -> {
            drawRoundRect(color, p(7f, 10f), Size(10f * u, 10f * u), androidx.compose.ui.geometry.CornerRadius(2.5f * u), style = line)
            seg(9f, 10f, 9f, 5f); seg(11.5f, 10f, 11.5f, 3.5f); seg(14f, 10f, 14f, 5f); seg(16.5f, 11f, 16.5f, 7f)
            seg(7f, 14f, 4.5f, 11.5f)
        }
        StripIcon.Rectangle -> drawRect(color, p(4f, 6f), Size(16f * u, 12f * u), style = line)
        StripIcon.Diamond -> poly(12f to 3f, 21f to 12f, 12f to 21f, 3f to 12f, close = true)
        StripIcon.Ellipse -> drawOval(color, p(3f, 6f), Size(18f * u, 12f * u), style = line)
        StripIcon.Arrow -> { seg(3f, 12f, 20f, 12f); poly(14f to 6f, 20f to 12f, 14f to 18f) }
        StripIcon.Line -> { seg(4f, 19f, 20f, 5f); drawCircle(color, 1.6f * u, p(4f, 19f)); drawCircle(color, 1.6f * u, p(20f, 5f)) }
        StripIcon.Freedraw -> {
            val path = Path().apply {
                moveTo(3f * u, 15f * u); quadraticTo(6f * u, 4f * u, 10f * u, 12f * u); quadraticTo(14f * u, 20f * u, 21f * u, 8f * u)
            }
            drawPath(path, color, style = line)
        }
        StripIcon.Text -> { seg(5f, 5f, 19f, 5f); seg(12f, 5f, 12f, 20f) }
        StripIcon.Image -> {
            drawRect(color, p(3f, 5f), Size(18f * u, 14f * u), style = line)
            poly(3f to 17f, 9f to 11f, 13f to 15f, 16f to 12f, 21f to 17f)
            drawCircle(color, 1.6f * u, p(16.5f, 8.5f))
        }
        StripIcon.Frame -> { seg(8f, 3f, 8f, 21f); seg(16f, 3f, 16f, 21f); seg(3f, 8f, 21f, 8f); seg(3f, 16f, 21f, 16f) }
        StripIcon.Eraser -> { poly(4f to 14f, 13f to 5f, 20f to 12f, 13f to 19f, close = true); seg(9f, 9f, 16f, 16f); seg(11f, 19f, 21f, 19f) }
        StripIcon.Point -> {
            drawCircle(color, 3f * u, p(12f, 12f), style = line)
            seg(12f, 2f, 12f, 7f); seg(12f, 17f, 12f, 22f); seg(2f, 12f, 7f, 12f); seg(17f, 12f, 22f, 12f)
        }
        StripIcon.Arc -> drawArc(color, 180f, 180f, false, p(4f, 8f), Size(16f * u, 16f * u), style = line)
        StripIcon.Undo -> drawTurnArrow(color, u, line)
        StripIcon.Redo -> scale(-1f, 1f, pivot = p(12f, 12f)) { drawTurnArrow(color, u, line) }
        StripIcon.Delete -> {
            seg(4f, 7f, 20f, 7f); poly(9f to 7f, 9f to 4f, 15f to 4f, 15f to 7f)
            poly(6f to 7f, 7f to 20f, 17f to 20f, 18f to 7f)
        }
        StripIcon.Constrain -> {
            drawCircle(color, 3.5f * u, p(6f, 12f), style = line); drawCircle(color, 3.5f * u, p(18f, 12f), style = line)
            seg(9.5f, 12f, 14.5f, 12f)
        }
        StripIcon.LockClosed -> {
            drawRect(color, p(6f, 11f), Size(12f * u, 9f * u), style = line)
            drawArc(color, 180f, 180f, false, p(8.5f, 4f), Size(7f * u, 8f * u), style = line)
        }
        StripIcon.LockOpen -> {
            drawRect(color, p(6f, 11f), Size(12f * u, 9f * u), style = line)
            drawArc(color, 180f, 180f, false, p(8.5f, 1f), Size(7f * u, 8f * u), style = line)
            seg(8.5f, 5f, 8.5f, 11f)
        }
        StripIcon.More -> poly(7f to 10f, 12f to 15f, 17f to 10f)
    }
}

private fun DrawScope.drawTurnArrow(color: Color, u: Float, line: Stroke) {
    val head = Path().apply { moveTo(9f * u, 6f * u); lineTo(4f * u, 11f * u); lineTo(9f * u, 16f * u) }
    val tail = Path().apply {
        moveTo(4f * u, 11f * u); lineTo(14f * u, 11f * u); quadraticTo(20f * u, 11f * u, 20f * u, 15.5f * u)
        quadraticTo(20f * u, 20f * u, 14f * u, 20f * u); lineTo(10f * u, 20f * u)
    }
    drawPath(head, color, style = line)
    drawPath(tail, color, style = line)
}
