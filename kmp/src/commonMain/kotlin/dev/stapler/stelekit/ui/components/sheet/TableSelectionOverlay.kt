// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
internal fun outlineColor(preferred: Color, background: Color): Color {
    if (contrastRatio(preferred, background) >= OUTLINE_MIN_CONTRAST) return preferred
    return listOf(Color.Black, Color.White).maxBy { contrastRatio(it, background) }
}

/** Draws the outline on the [edges] that border the range; fill alone never signals selection. */
internal fun Modifier.selectionOutline(edges: OutlineEdges, color: Color, widthPx: Float = 2f): Modifier =
    if (!edges.any) this else drawBehind {
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
