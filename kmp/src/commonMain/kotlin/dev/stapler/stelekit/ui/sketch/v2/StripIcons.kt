// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.tools.ToolId

/**
 * What a chrome button shows. Drawn, not typed: the old text glyphs made Select and Arrow two near-identical diagonal arrows and
 * rendered as emoji or tofu on some fonts. Each icon is drawn on a 24 x 24 grid with one 2-unit stroke and its own silhouette:
 * the tools first, then the top bar, the view cluster and the mode icons. A concept with no standard glyph (Point, Arc, Constrain,
 * Snap) gets a custom one, and any lettering is drawn as strokes so it never depends on a font.
 */
enum class StripIcon {
    Select, Hand, Rectangle, Diamond, Ellipse, Arrow, Line, Freedraw, Text, Image, Frame, Eraser, Point, Arc,
    Undo, Redo, Delete, Constrain, LockClosed, LockOpen, More,
    Back, Overflow, Help, SaveOk, SaveBusy, SaveDirty, SaveFail, ZoomIn, ZoomOut, Grid, Snap, Finger, Technical,
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
private val ICON_DP = 24.dp
private const val STROKE = 2f

@Composable
internal fun StripIconView(icon: StripIcon, color: Color, modifier: Modifier = Modifier, size: Dp = ICON_DP) {
    Canvas(modifier.size(size)) { drawStripIcon(icon, color, this.size.width / GRID) }
}

/** Draws [icon] on a 24-unit grid scaled by [u] pixels per unit. */
fun DrawScope.drawStripIcon(icon: StripIcon, color: Color, u: Float) {
    val line = Stroke(STROKE * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val pen = IconPen(this, color, u, line)
    when (icon) {
        StripIcon.Select, StripIcon.Hand, StripIcon.Rectangle, StripIcon.Diamond, StripIcon.Ellipse, StripIcon.Arrow, StripIcon.Line,
        StripIcon.Freedraw, StripIcon.Text, StripIcon.Image, StripIcon.Frame, StripIcon.Eraser, StripIcon.Point, StripIcon.Arc,
        -> pen.tool(icon)
        StripIcon.Undo -> drawTurnArrow(color, u, line)
        StripIcon.Redo -> scale(-1f, 1f, pivot = Offset(12f * u, 12f * u)) { drawTurnArrow(color, u, line) }
        StripIcon.Delete, StripIcon.Constrain, StripIcon.LockClosed, StripIcon.LockOpen, StripIcon.More -> pen.action(icon)
        else -> pen.chrome(icon)
    }
}

/** The strokes of one icon: every shape is stated in 24-unit grid coordinates. */
private class IconPen(private val scope: DrawScope, private val color: Color, private val u: Float, private val line: Stroke) {
    private fun p(x: Float, y: Float) = Offset(x * u, y * u)

    private fun poly(vararg pts: Pair<Float, Float>, close: Boolean = false, fill: Boolean = false) {
        val path = Path().apply {
            moveTo(pts[0].first * u, pts[0].second * u)
            for (i in 1 until pts.size) lineTo(pts[i].first * u, pts[i].second * u)
            if (close) close()
        }
        scope.drawPath(path, color, style = if (fill) Fill else line)
    }

    private fun seg(x1: Float, y1: Float, x2: Float, y2: Float) = scope.drawLine(color, p(x1, y1), p(x2, y2), STROKE * u, StrokeCap.Round)

    private fun dot(x: Float, y: Float, r: Float = 1.6f) = scope.drawCircle(color, r * u, p(x, y))

    private fun ring(x: Float, y: Float, r: Float) = scope.drawCircle(color, r * u, p(x, y), style = line)

    private fun box(x: Float, y: Float, w: Float, h: Float, radius: Float = 0f) {
        if (radius == 0f) scope.drawRect(color, p(x, y), Size(w * u, h * u), style = line)
        else scope.drawRoundRect(color, p(x, y), Size(w * u, h * u), CornerRadius(radius * u), style = line)
    }

    private fun arc(x: Float, y: Float, w: Float, start: Float, sweep: Float) =
        scope.drawArc(color, start, sweep, false, p(x, y), Size(w * u, w * u), style = line)

    private fun curve(vararg q: Float) {
        val path = Path().apply {
            moveTo(q[0] * u, q[1] * u)
            var i = 2
            while (i + 3 < q.size) { quadraticTo(q[i] * u, q[i + 1] * u, q[i + 2] * u, q[i + 3] * u); i += 4 }
        }
        scope.drawPath(path, color, style = line)
    }

    fun tool(icon: StripIcon) {
        when (icon) {
            StripIcon.Select -> poly(6f to 3f, 6f to 19f, 10.5f to 15f, 14f to 21f, 16.5f to 19.8f, 13f to 14f, 19f to 14f, close = true, fill = true)
            StripIcon.Hand -> {
                box(7f, 10f, 10f, 10f, 2.5f)
                seg(9f, 10f, 9f, 5f); seg(11.5f, 10f, 11.5f, 3.5f); seg(14f, 10f, 14f, 5f); seg(16.5f, 11f, 16.5f, 7f)
                seg(7f, 14f, 4.5f, 11.5f)
            }
            StripIcon.Rectangle -> box(4f, 6f, 16f, 12f)
            StripIcon.Diamond -> poly(12f to 3f, 21f to 12f, 12f to 21f, 3f to 12f, close = true)
            StripIcon.Ellipse -> scope.drawOval(color, p(3f, 6f), Size(18f * u, 12f * u), style = line)
            StripIcon.Arrow -> { seg(3f, 12f, 20f, 12f); poly(14f to 6f, 20f to 12f, 14f to 18f) }
            StripIcon.Line -> { seg(4f, 19f, 20f, 5f); dot(4f, 19f); dot(20f, 5f) }
            StripIcon.Freedraw -> { curve(3f, 17f, 6f, 6f, 10f, 13f, 14f, 20f, 21f, 9f); dot(21f, 9f, 2.2f) }
            StripIcon.Text -> { seg(5f, 5f, 19f, 5f); seg(12f, 5f, 12f, 19f); seg(8.5f, 19f, 15.5f, 19f) }
            StripIcon.Image -> { box(3f, 5f, 18f, 14f); poly(3f to 17f, 9f to 11f, 13f to 15f, 16f to 12f, 21f to 17f); dot(16.5f, 8.5f) }
            StripIcon.Frame -> {
                poly(4f to 9f, 4f to 4f, 9f to 4f); poly(15f to 4f, 20f to 4f, 20f to 9f)
                poly(20f to 15f, 20f to 20f, 15f to 20f); poly(9f to 20f, 4f to 20f, 4f to 15f)
            }
            StripIcon.Eraser -> { poly(4f to 14f, 13f to 5f, 20f to 12f, 13f to 19f, close = true); seg(9f, 9f, 16f, 16f); seg(11f, 19f, 21f, 19f) }
            StripIcon.Point -> {
                ring(12f, 12f, 4f); dot(12f, 12f, 1.4f)
                seg(12f, 2f, 12f, 6f); seg(12f, 18f, 12f, 22f); seg(2f, 12f, 6f, 12f); seg(18f, 12f, 22f, 12f)
            }
            else -> { arc(4f, 8f, 16f, 180f, 180f); dot(4f, 16f); dot(20f, 16f); dot(12f, 16f, 1.1f) }
        }
    }

    fun action(icon: StripIcon) {
        when (icon) {
            StripIcon.Delete -> { seg(4f, 7f, 20f, 7f); poly(9f to 7f, 9f to 4f, 15f to 4f, 15f to 7f); poly(6f to 7f, 7f to 20f, 17f to 20f, 18f to 7f) }
            StripIcon.Constrain -> { box(2.5f, 8.5f, 11f, 7f, 3.5f); box(10.5f, 8.5f, 11f, 7f, 3.5f) }
            StripIcon.LockClosed -> { box(6f, 11f, 12f, 9f); arc(8.5f, 4f, 7f, 180f, 180f) }
            StripIcon.LockOpen -> { box(6f, 11f, 12f, 9f); arc(8.5f, 2f, 7f, 180f, 180f); seg(8.5f, 5.5f, 8.5f, 11f) }
            else -> poly(7f to 9f, 17f to 9f, 12f to 15.5f, close = true, fill = true)
        }
    }

    fun chrome(icon: StripIcon) {
        when (icon) {
            StripIcon.Back -> { seg(20f, 12f, 5f, 12f); poly(11f to 6f, 5f to 12f, 11f to 18f) }
            StripIcon.Overflow -> { dot(12f, 5f, 1.9f); dot(12f, 12f, 1.9f); dot(12f, 19f, 1.9f) }
            StripIcon.Help -> {
                ring(12f, 12f, 9f)
                curve(9.2f, 9.6f, 9.2f, 6.6f, 12f, 6.6f, 14.8f, 6.6f, 14.8f, 9.4f, 14.8f, 11.4f, 12f, 12.3f)
                seg(12f, 12.3f, 12f, 14f); dot(12f, 17.2f, 1.2f)
            }
            StripIcon.SaveOk -> { ring(12f, 12f, 9f); poly(8f to 12.5f, 11f to 15.5f, 16.5f to 9f) }
            StripIcon.SaveBusy -> { arc(4f, 4f, 16f, -60f, 270f); poly(17f to 3f, 17.5f to 8f, 12.5f to 7.5f) }
            StripIcon.SaveDirty -> dot(12f, 12f, 5f)
            StripIcon.SaveFail -> { ring(12f, 12f, 9f); seg(12f, 7f, 12f, 13f); dot(12f, 16.6f, 1.2f) }
            StripIcon.ZoomIn -> { ring(10f, 10f, 6.5f); seg(15f, 15f, 20.5f, 20.5f); seg(7f, 10f, 13f, 10f); seg(10f, 7f, 10f, 13f) }
            StripIcon.ZoomOut -> { ring(10f, 10f, 6.5f); seg(15f, 15f, 20.5f, 20.5f); seg(7f, 10f, 13f, 10f) }
            StripIcon.Grid -> { box(4f, 4f, 16f, 16f); seg(9.3f, 4f, 9.3f, 20f); seg(14.7f, 4f, 14.7f, 20f); seg(4f, 9.3f, 20f, 9.3f); seg(4f, 14.7f, 20f, 14.7f) }
            StripIcon.Snap -> {
                arc(6f, 8f, 12f, 0f, 180f)
                seg(6f, 8f, 6f, 14f); seg(18f, 8f, 18f, 14f); seg(3.5f, 8f, 8.5f, 8f); seg(15.5f, 8f, 20.5f, 8f)
                dot(6f, 3.5f, 1.2f); dot(18f, 3.5f, 1.2f)
            }
            StripIcon.Finger -> { box(9.5f, 3f, 5f, 11f, 2.5f); box(6.5f, 11f, 11f, 10f, 3f) }
            else -> { box(3f, 8f, 18f, 9f); seg(7f, 8f, 7f, 12f); seg(11f, 8f, 11f, 11f); seg(15f, 8f, 15f, 12f); seg(19f, 8f, 19f, 11f) }
        }
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
