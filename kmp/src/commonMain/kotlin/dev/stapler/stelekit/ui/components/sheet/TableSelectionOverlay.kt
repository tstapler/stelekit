// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.GridSelection

/** Which sides of a cell lie on the border of the selected range, so the range reads as one outlined block. */
internal data class OutlineEdges(val top: Boolean, val bottom: Boolean, val left: Boolean, val right: Boolean) {
    val any: Boolean get() = top || bottom || left || right

    companion object {
        val None = OutlineEdges(top = false, bottom = false, left = false, right = false)
    }
}

internal fun outlineEdges(selection: GridSelection?, address: CellAddress): OutlineEdges {
    if (selection == null || address !in selection) return OutlineEdges.None
    val r = selection.range
    return OutlineEdges(
        top = address.row == r.topLeft.row,
        bottom = address.row == r.bottomRight.row,
        left = address.col == r.topLeft.col,
        right = address.col == r.bottomRight.col,
    )
}

/** WCAG contrast ratio between two opaque colors (1 to 21). */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** Minimum outline contrast against the cell background (WCAG 1.4.11 non-text contrast). */
internal const val OUTLINE_MIN_CONTRAST = 3.0

/** Picks [preferred] when it clears [OUTLINE_MIN_CONTRAST] on [background], else the more contrasting of black and white. */
internal fun outlineColor(preferred: Color, background: Color): Color = readableOn(preferred, listOf(background), OUTLINE_MIN_CONTRAST)

/** [preferred] when it clears [min] on every one of [backgrounds], else the black or white that clears it best on the worst one. */
internal fun readableOn(preferred: Color, backgrounds: List<Color>, min: Double): Color {
    if (backgrounds.all { contrastRatio(preferred, it) >= min }) return preferred
    return listOf(Color.Black, Color.White).maxBy { ink -> backgrounds.minOf { contrastRatio(ink, it) } }
}

/** The outline color for a cell on any of [backgrounds] (surface, zebra, formula tint, header): 3:1 on all of them. */
internal fun sheetOutlineColor(preferred: Color, backgrounds: List<Color>): Color = readableOn(preferred, backgrounds, OUTLINE_MIN_CONTRAST)

/** Error text must reach 4.5:1 (ux.md 3.5) on every cell background it can sit on. */
internal fun sheetErrorColor(preferred: Color, backgrounds: List<Color>): Color = readableOn(preferred, backgrounds, ERROR_TEXT_MIN_CONTRAST)

internal const val ERROR_TEXT_MIN_CONTRAST = 4.5

/** The cell backgrounds of a theme, translucent tints composited over the surface. */
internal fun cellBackgrounds(scheme: androidx.compose.material3.ColorScheme): List<Color> = listOf(
    scheme.surface,
    scheme.onSurface.copy(alpha = 0.06f).compositeOver(scheme.surface),
    scheme.primary.copy(alpha = 0.08f).compositeOver(scheme.surface),
    scheme.surfaceVariant,
)

/** Draws the outline on the [edges] that border the range, [widthDp] wide (2dp floor, ux.md 3.5); fill alone never signals selection. */
internal fun Modifier.selectionOutline(edges: OutlineEdges, color: Color, widthDp: Float = OutlineWidths.NORMAL_DP): Modifier =
    if (!edges.any) this else drawBehind { drawSelectionOutline(edges, color, widthDp) }

internal fun DrawScope.drawSelectionOutline(edges: OutlineEdges, color: Color, widthDp: Float) {
    val widthPx = widthDp * density
    val half = widthPx / 2f
    if (edges.top) drawLine(color, Offset(0f, half), Offset(size.width, half), widthPx)
    if (edges.bottom) drawLine(color, Offset(0f, size.height - half), Offset(size.width, size.height - half), widthPx)
    if (edges.left) drawLine(color, Offset(half, 0f), Offset(half, size.height), widthPx)
    if (edges.right) drawLine(color, Offset(size.width - half, 0f), Offset(size.width - half, size.height), widthPx)
}

/** `selected = true` on each selected cell, the non-visual half of "selection is visible without color alone". */
internal fun SemanticsPropertyReceiver.markSelected(isSelected: Boolean) {
    if (isSelected) selected = true
}
