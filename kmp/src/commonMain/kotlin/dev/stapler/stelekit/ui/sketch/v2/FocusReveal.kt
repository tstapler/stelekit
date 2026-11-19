// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.view.ScreenRect
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchA11yStrings.Overlay as O
import kotlin.math.abs

/** A rectangle that sits over the canvas and can hide a focused node: [name] is what an audit failure cites. */
data class OverlayRect(val name: String, val rect: ScreenRect) {
    /** Half-open, so a point exactly on the far edge is clear of the overlay. */
    fun covers(x: Double, y: Double): Boolean = x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom
}

/**
 * Focus reveal (Story 3.6.6, WCAG 2.4.11, UX-125, U8): when keyboard focus lands on a canvas element or handle under an
 * overlay, the view pans (never zooms) by the least amount that puts the node's centre clear of every overlay and inside the
 * canvas. The pan is a view change, never an undo entry. Pure and in dp, so the audit walks every window of the chrome table.
 */
object FocusReveal {
    private const val HALF = 0.5
    private const val NO_PAN = 0.0

    /**
     * Every overlay the chrome table lets sit on the canvas of [plan] (canvas of [canvasWidthDp] by `plan.canvasCollapsedDp`),
     * in canvas coordinates: the toast lane at the top start, the view cluster and the action chips at the bottom, an edge strip
     * at each protected end, a half-height bottom sheet, and the soft keyboard when [keyboardDp] is above zero.
     */
    fun overlays(plan: ChromePlan, fontScale: Double, sheetOpen: Boolean, keyboardDp: Int = 0): List<OverlayRect> {
        val width = plan.canvasWidthDp.toDouble()
        val height = plan.canvasCollapsedDp.toDouble()
        val gutter = ChromeBudget.GUTTER_DP.toDouble()
        val target = ChromeBudget.TOOL_TARGET_DP.toDouble()
        val strips = ChromeBudget.edgeStrips(plan.canvasWidthDp)
        val clusterW = ChromeBudget.clusterWidthDp(plan.overlay.clusterRows).toDouble()
        val clusterH = ChromeBudget.clusterHeightDp(plan.overlay.clusterRows).toDouble()
        val clusterLeft = width - gutter - clusterW - target * strips
        val out = mutableListOf<OverlayRect>()
        out += OverlayRect(O.VIEW_CLUSTER, ScreenRect(clusterLeft, height - clusterH - gutter / 2, clusterLeft + clusterW, height - gutter / 2))
        val chipTop = if (plan.overlay.chipsAbove) height - plan.overlay.heightDp - gutter / 2 else height - target - gutter / 2
        out += OverlayRect(O.CHIP_ROW, ScreenRect(gutter, chipTop, gutter + ChromeBudget.CHIP_MIN_DP, chipTop + target))
        val toastHeight = plan.toastCount * (ChromeText.toastRowDp(fontScale) + ChromeBudget.TOAST_GAP_DP).toDouble()
        if (toastHeight > 0) out += OverlayRect(O.TOASTS, ScreenRect(gutter / 2, gutter / 2, width * HALF, gutter / 2 + toastHeight))
        for (strip in 0 until strips) {
            val left = if (strip == 0) width - target else 0.0
            out += OverlayRect(O.edgeStrip(strip + 1), ScreenRect(left, 0.0, left + target, height))
        }
        if (sheetOpen) out += OverlayRect(O.SHEET, ScreenRect(0.0, height * HALF, width, height))
        if (keyboardDp > 0) out += OverlayRect(O.KEYBOARD, ScreenRect(0.0, height - keyboardDp, width, height))
        return out
    }

    /** The first overlay that covers the point ([x], [y]), or null. */
    fun coveredBy(x: Double, y: Double, overlays: List<OverlayRect>): OverlayRect? = overlays.firstOrNull { it.covers(x, y) }

    /**
     * The view after the least pan that brings the node centred at screen ([x], [y]) inside [canvas] and clear of [overlays];
     * [viewport] itself when it is already clear. Null when no point of the canvas is clear (a degraded window claims no
     * minimum canvas), so the caller can name the window instead of panning in circles. Zoom never changes.
     */
    fun reveal(viewport: SketchViewport, x: Double, y: Double, canvas: ScreenRect, overlays: List<OverlayRect>): SketchViewport? {
        val moves = candidateMoves(x, y, canvas, overlays).filter { (dx, dy) ->
            val nx = x + dx
            val ny = y + dy
            nx >= canvas.left && nx < canvas.right && ny >= canvas.top && ny < canvas.bottom && coveredBy(nx, ny, overlays) == null
        }
        val best = moves.minByOrNull { (dx, dy) -> abs(dx) + abs(dy) } ?: return null
        return if (best.first == NO_PAN && best.second == NO_PAN) viewport else viewport.panBy(best.first, best.second)
    }

    /**
     * Every pan that puts the centre exactly on the outer edge of an overlay or of the canvas on either axis, alone or combined
     * with one on the other axis (an edge strip and a sheet can both cover the same corner), and no pan.
     */
    private fun candidateMoves(x: Double, y: Double, canvas: ScreenRect, overlays: List<OverlayRect>): List<Pair<Double, Double>> {
        val dxs = mutableListOf(NO_PAN, canvas.left - x, canvas.right - 1 - x)
        val dys = mutableListOf(NO_PAN, canvas.top - y, canvas.bottom - 1 - y)
        for (o in overlays) {
            dxs += listOf(o.rect.left - 1 - x, o.rect.right - x)
            dys += listOf(o.rect.top - 1 - y, o.rect.bottom - y)
        }
        return dxs.flatMap { dx -> dys.map { dy -> dx to dy } }
    }
}
