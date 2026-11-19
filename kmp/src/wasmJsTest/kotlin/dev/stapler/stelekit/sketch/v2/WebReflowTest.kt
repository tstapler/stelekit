// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.ui.sketch.v2.ChromeBudget
import dev.stapler.stelekit.ui.sketch.v2.PanelHost
import dev.stapler.stelekit.ui.sketch.v2.StripPlacement
import dev.stapler.stelekit.ui.sketch.v2.prefersReducedMotion
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun stubMatchMedia(matches: Boolean): Unit =
    js("(function() { window.__realMatchMedia = window.__realMatchMedia || window.matchMedia; window.matchMedia = function(q) { window.__lastMediaQuery = q; return { matches: matches, media: q }; }; })()")

private fun restoreMatchMedia(): Unit = js("(function() { if (window.__realMatchMedia) { window.matchMedia = window.__realMatchMedia; } })()")

private fun lastMediaQuery(): String = js("window.__lastMediaQuery || ''")

/**
 * Task 3.6.6d (UX-113, D-28): the web actuals of the text and motion criteria, in a real browser. `prefers-reduced-motion:
 * reduce` is read through the browser's own `matchMedia` and maps to the same reduced-motion signal an Android animation scale
 * of 0 gives; the layout at 320 CSS px (a 1280 px page at 400% zoom) is one column with panels as bottom sheets.
 * NOT covered here: WCAG 1.4.12 text spacing. Compose for web paints to a canvas, so a user-agent text-spacing stylesheet has no
 * text node to restyle; that criterion stays an owner check with a real browser and assistive technology.
 */
class WebReflowTest {
    @AfterTest
    fun restore() = restoreMatchMedia()

    @Test
    fun ux113_web_should_MapPrefersReducedMotionToTheReducedMotionSignal() {
        stubMatchMedia(matches = true)
        assertTrue(prefersReducedMotion(), "prefers-reduced-motion: reduce means animations are disabled")
        assertTrue("prefers-reduced-motion" in lastMediaQuery() && "reduce" in lastMediaQuery(), "the query asked was ${lastMediaQuery()}")
        stubMatchMedia(matches = false)
        assertFalse(prefersReducedMotion(), "no preference leaves animation on")
    }

    @Test
    fun ux113_web_should_ReflowToOneColumnAt320CssPx_And400PercentZoomOf1280() {
        val atZoom = 1280 / 4
        assertEquals(320, atZoom)
        for ((width, height) in listOf(320 to 256, 320 to 640)) {
            val layout = ChromeBudget.layout(width, height)
            assertTrue(layout.panels != PanelHost.PersistentPane, "$width x $height: a panel never takes a second column")
            assertEquals(1, ChromeBudget.edgeStrips(width), "$width x $height: one edge strip on a compact width")
            assertTrue(ChromeBudget.canvasWidthDp(layout, width) in 1..width, "$width x $height: the canvas never needs more than the page width")
        }
        val tall = ChromeBudget.layout(320, 640)
        assertEquals(StripPlacement.BottomDock to PanelHost.BottomSheet, tall.placement to tall.panels, "a tall narrow page docks the strip and shows panels as sheets")
    }

    @Test
    fun ux113_web_should_KeepEveryControlInTheTablesDegradedWindowAtBothFontScales() {
        val windows = ChromeTableParser.parseFixture().windows.filter { it.name == ZOOM_WINDOW }
        assertTrue(windows.isNotEmpty(), "the chrome table lists the 400% zoom window")
        for (w in windows) {
            assertFalse(w.guaranteed, "${w.name} is the degraded window: no minimum canvas is claimed")
            val plan = ChromeBudget.plan(w.widthDp, w.heightDp, w.fontScale)
            assertTrue(plan.canvasWidthDp >= ChromeBudget.MIN_CANVAS_WIDTH_DP, "${w.name} at ${w.fontScale}: the width still holds the minimum canvas width")
            assertTrue(plan.edgeStrips == 1 && plan.layout.panels != PanelHost.PersistentPane, "${w.name} at ${w.fontScale}: one column")
        }
    }

    private companion object {
        const val ZOOM_WINDOW = "web 400% zoom"
    }
}
