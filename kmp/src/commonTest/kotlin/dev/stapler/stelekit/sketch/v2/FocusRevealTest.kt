// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.view.ScreenRect
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.ChromeBudget
import dev.stapler.stelekit.ui.sketch.v2.FocusReveal
import dev.stapler.stelekit.ui.sketch.v2.OverlayRect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 3.6.6, Task 3.6.6a (UX-125, U8, WCAG 2.4.11): a focused canvas element or handle under an overlay makes the view pan
 * (never zoom) by the least amount that clears every overlay; no focused node's centre stays covered in any guaranteed window
 * of the chrome table with the zoom cluster, chips, edge strips, toasts and a half-height sheet all showing.
 */
class FocusRevealTest {
    private val table = ChromeTableParser.parseFixture()
    private val start = SketchViewport.of(zoom = 1.5, panX = 40.0, panY = 25.0)

    private fun canvas(w: Double, h: Double) = ScreenRect(0.0, 0.0, w, h)

    @Test
    fun focusedElementUnderOverlay_should_PanLeastAmountClearOfAllOverlays_AndNotZoom() {
        val overlay = OverlayRect("view cluster", ScreenRect(200.0, 100.0, 300.0, 180.0))
        val revealed = assertNotNull(FocusReveal.reveal(start, x = 250.0, y = 150.0, canvas = canvas(600.0, 400.0), overlays = listOf(overlay)))
        assertEquals(start.zoom, revealed.zoom, "the view pans, it never zooms")
        val moved = abs(revealed.panX - start.panX) + abs(revealed.panY - start.panY)
        assertEquals(30.0, moved, "least pan: 30 to leave through the bottom edge (50 to the left, 50 right, 51 up)")
        assertNull(FocusReveal.coveredBy(250.0 + revealed.panX - start.panX, 150.0 + revealed.panY - start.panY, listOf(overlay)))
    }

    @Test
    fun clearNode_should_KeepTheViewUnchanged() {
        val overlay = OverlayRect("toasts", ScreenRect(0.0, 0.0, 50.0, 50.0))
        assertEquals(start, FocusReveal.reveal(start, 300.0, 300.0, canvas(600.0, 400.0), listOf(overlay)))
    }

    @Test
    fun pan_should_NotBeAnUndoEntry_SinceItReturnsOnlyAViewport() {
        val result: SketchViewport? = FocusReveal.reveal(start, 5.0, 5.0, canvas(600.0, 400.0), listOf(OverlayRect("toasts", ScreenRect(0.0, 0.0, 50.0, 50.0))))
        assertNotNull(result)
    }

    @Test
    fun noFocusedNodeCentre_should_BeCoveredAtAnyGuaranteedWindowOfTheTable_WithEveryOverlayShowing() {
        val failures = mutableListOf<String>()
        for (w in table.windows.filter { it.guaranteed }) {
            val plan = ChromeBudget.plan(w.widthDp, w.heightDp, w.fontScale)
            val overlays = FocusReveal.overlays(plan, w.fontScale, sheetOpen = true)
            val area = canvas(plan.canvasWidthDp.toDouble(), plan.canvasCollapsedDp.toDouble())
            for (gx in 0..GRID) for (gy in 0..GRID) {
                val x = area.width * gx / GRID.toDouble() - if (gx == GRID) 1.0 else 0.0
                val y = area.height * gy / GRID.toDouble() - if (gy == GRID) 1.0 else 0.0
                val view = FocusReveal.reveal(start, x, y, area, overlays)
                if (view == null) {
                    failures += "${w.name} ${w.widthDp}x${w.heightDp}: no clear point for a node at ($x, $y)"
                    continue
                }
                val cx = x + view.panX - start.panX
                val cy = y + view.panY - start.panY
                FocusReveal.coveredBy(cx, cy, overlays)?.let { failures += "${w.name}: node at ($x, $y) still under ${it.name}" }
                if (view.zoom != start.zoom) failures += "${w.name}: the reveal zoomed"
            }
        }
        assertTrue(failures.isEmpty(), failures.take(MAX_REPORTED).joinToString("\n"))
    }

    @Test
    fun aDegradedWindow_should_ReportNoClearPoint_InsteadOfPanningInCircles() {
        val everything = listOf(OverlayRect("bottom sheet", ScreenRect(0.0, 0.0, 600.0, 400.0)))
        assertNull(FocusReveal.reveal(start, 10.0, 10.0, canvas(600.0, 400.0), everything))
    }

    private companion object {
        const val GRID = 4
        const val MAX_REPORTED = 20
    }
}
