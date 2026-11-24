// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import dev.stapler.stelekit.sketch.v2.snap.GridLines
import dev.stapler.stelekit.sketch.v2.snap.GridSettings
import dev.stapler.stelekit.sketch.v2.snap.PointSnapHit
import dev.stapler.stelekit.sketch.v2.snap.PointSnapKind
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchHostStrings

object SnapMarkerTags {
    const val MARKER = "snap-point-marker"
    const val GRID = "snap-grid"
}

/**
 * The glyph and name of the point a drag has locked onto (the CAD vocabulary: square endpoint, triangle midpoint, cross
 * intersection, circle centre, right-angle perpendicular, circle-on-a-line tangent), at [hit]'s place on the canvas. Draws
 * nothing for null. It takes no input and is hidden from screen readers like the guide lines (the Snap toggle announces).
 */
@Composable
fun SnapPointMarker(hit: PointSnapHit?, viewport: SketchViewport, modifier: Modifier = Modifier) {
    if (hit == null) return
    val ink = MaterialTheme.colorScheme.tertiary
    val density = LocalDensity.current
    val x = (hit.x * viewport.zoom + viewport.panX).toFloat()
    val y = (hit.y * viewport.zoom + viewport.panY).toFloat()
    Box(modifier.fillMaxSize().clearAndSetSemantics { testTag = SnapMarkerTags.MARKER }) {
        Canvas(Modifier.fillMaxSize()) { drawSnapGlyph(hit.kind, Offset(x, y), GLYPH_DP.dp.toPx(), ink, GLYPH_STROKE_DP.dp.toPx()) }
        val gap = with(density) { LABEL_GAP_DP.dp.roundToPx() }
        Text(
            SketchHostStrings.Snap.kindName(hit.kind),
            Modifier.offset { IntOffset(x.toInt() + gap, y.toInt() + gap) }.clip(RoundedCornerShape(LABEL_RADIUS_DP.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = LABEL_ALPHA)).padding(horizontal = LABEL_PAD_DP.dp),
            color = ink,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/** One snap glyph of [kind] centred on [at], [size] across, drawn with [width] strokes. */
fun DrawScope.drawSnapGlyph(kind: PointSnapKind, at: Offset, size: Float, ink: Color, width: Float) {
    val h = size / 2f
    val line = Stroke(width = width)
    when (kind) {
        PointSnapKind.Endpoint -> drawRect(ink, Offset(at.x - h, at.y - h), Size(size, size), style = line)
        PointSnapKind.Midpoint -> {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(at.x, at.y - h)
                lineTo(at.x + h, at.y + h)
                lineTo(at.x - h, at.y + h)
                close()
            }
            drawPath(path, ink, style = line)
        }
        PointSnapKind.Intersection -> {
            drawLine(ink, Offset(at.x - h, at.y - h), Offset(at.x + h, at.y + h), strokeWidth = width)
            drawLine(ink, Offset(at.x - h, at.y + h), Offset(at.x + h, at.y - h), strokeWidth = width)
        }
        PointSnapKind.Centre -> drawCircle(ink, h, at, style = line)
        PointSnapKind.Perpendicular -> {
            drawLine(ink, Offset(at.x - h, at.y + h), Offset(at.x + h, at.y + h), strokeWidth = width)
            drawLine(ink, Offset(at.x - h, at.y + h), Offset(at.x - h, at.y - h), strokeWidth = width)
            drawLine(ink, Offset(at.x - h, at.y), Offset(at.x, at.y), strokeWidth = width)
            drawLine(ink, Offset(at.x, at.y), Offset(at.x, at.y + h), strokeWidth = width)
        }
        PointSnapKind.Tangent -> {
            drawCircle(ink, h * TANGENT_CIRCLE, Offset(at.x, at.y + h * TANGENT_DROP), style = line)
            drawLine(ink, Offset(at.x - h, at.y - h), Offset(at.x + h, at.y - h), strokeWidth = width)
        }
    }
}

/**
 * The coarse grid of the sheet over the canvas: faint minor lines at the grid step and heavier major lines every
 * [GridSettings.majorEvery] steps, thinned by [GridLines] so zoomed-out lines never crowd. Nothing when [grid] is null or off.
 */
@Composable
fun GridOverlay(grid: GridSettings?, viewport: SketchViewport, modifier: Modifier = Modifier) {
    if (grid == null || !grid.enabled) return
    val ink = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current.density.toDouble()
    Canvas(modifier.fillMaxSize().clearAndSetSemantics { testTag = SnapMarkerTags.GRID }) {
        val zoom = viewport.zoom
        val xs = GridLines.along(grid, (0.0 - viewport.panX) / zoom, (size.width - viewport.panX) / zoom, zoom, density)
        val ys = GridLines.along(grid, (0.0 - viewport.panY) / zoom, (size.height - viewport.panY) / zoom, zoom, density)
        val hairline = 1.dp.toPx()
        for (line in xs) {
            val px = (line.position * zoom + viewport.panX).toFloat()
            drawLine(ink.copy(alpha = if (line.major) MAJOR_ALPHA else MINOR_ALPHA), Offset(px, 0f), Offset(px, size.height), strokeWidth = if (line.major) hairline * 2 else hairline)
        }
        for (line in ys) {
            val py = (line.position * zoom + viewport.panY).toFloat()
            drawLine(ink.copy(alpha = if (line.major) MAJOR_ALPHA else MINOR_ALPHA), Offset(0f, py), Offset(size.width, py), strokeWidth = if (line.major) hairline * 2 else hairline)
        }
    }
}

private const val GLYPH_DP = 14
private const val GLYPH_STROKE_DP = 2
private const val LABEL_GAP_DP = 12
private const val LABEL_RADIUS_DP = 4
private const val LABEL_PAD_DP = 4
private const val LABEL_ALPHA = 0.85f
private const val TANGENT_CIRCLE = 0.6f
private const val TANGENT_DROP = 0.35f
private const val MINOR_ALPHA = 0.06f
private const val MAJOR_ALPHA = 0.16f
