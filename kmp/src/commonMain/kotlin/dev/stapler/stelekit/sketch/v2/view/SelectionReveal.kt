// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.view

import kotlin.math.max
import kotlin.math.min

/** The part of the canvas a sheet leaves visible, in screen pixels. */
data class ScreenRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
}

/**
 * Result of revealing a selection above or beside an open sheet (UX-125, Story 3.5.4a). [viewport] is the view to use;
 * [selectionLargerThanVisible] asks the status line to say [LARGER_THAN_VISIBLE]. The pan is a view change, never an undo
 * entry; [restoreOnClose] returns the earlier view once the sheet closes, unless the user moved the view meanwhile.
 */
data class RevealResult(val viewport: SketchViewport, val selectionLargerThanVisible: Boolean, private val before: SketchViewport) {
    val panned: Boolean get() = viewport != before

    fun restoreOnClose(current: SketchViewport): SketchViewport = if (panned && current == viewport) before else current

    companion object {
        const val LARGER_THAN_VISIBLE = "Selection is larger than the visible area"
    }
}

object SelectionReveal {
    /** Pans (never zooms) so [selection] sits inside [visible]; a taller selection keeps its top edge visible. */
    fun reveal(viewport: SketchViewport, selection: SceneBounds, visible: ScreenRect): RevealResult {
        val left = selection.minX * viewport.zoom + viewport.panX
        val right = selection.maxX * viewport.zoom + viewport.panX
        val top = selection.minY * viewport.zoom + viewport.panY
        val bottom = selection.maxY * viewport.zoom + viewport.panY
        val tooTall = bottom - top > visible.height
        val tooWide = right - left > visible.width
        val dx = shift(left, right, visible.left, visible.right, tooWide)
        val dy = shift(top, bottom, visible.top, visible.bottom, tooTall)
        return RevealResult(viewport.panBy(dx, dy), tooTall || tooWide, viewport)
    }

    /** The smallest move that puts [lo, hi] inside [from, to]; when it cannot fit, the low edge is placed at [from]. */
    private fun shift(lo: Double, hi: Double, from: Double, to: Double, cannotFit: Boolean): Double = when {
        cannotFit -> from - lo
        lo < from -> from - lo
        hi > to -> to - hi
        else -> 0.0
    }

    /** The visible rect above a bottom sheet of [sheetHeightPx] in a canvas of [width] x [height] pixels. */
    fun aboveBottomSheet(width: Double, height: Double, sheetHeightPx: Double): ScreenRect = ScreenRect(0.0, 0.0, width, max(0.0, height - sheetHeightPx))

    /** The visible rect beside a side sheet of [sheetWidthPx] that covers the trailing edge. */
    fun besideSideSheet(width: Double, height: Double, sheetWidthPx: Double): ScreenRect = ScreenRect(0.0, 0.0, min(width, max(0.0, width - sheetWidthPx)), height)
}
