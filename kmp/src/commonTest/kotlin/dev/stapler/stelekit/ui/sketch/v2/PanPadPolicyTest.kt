// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.ChromeTableParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 3.6.3d (UX-120, WCAG 2.5.7): the pan pad and sheet-size rules, with every figure checked against the chrome table. */
class PanPadPolicyTest {
    private val table = ChromeTableParser.parseFixture()

    @Test
    fun constants_should_EqualTheChromeTableParameters() {
        assertEquals(table.param("padCell"), PanPadPolicy.CELL_DP)
        assertEquals(table.param("padGridCells"), PanPadPolicy.GRID_CELLS)
        assertEquals(table.param("padRow"), PanPadPolicy.ROW_CELLS)
        assertEquals(table.param("padMaxPct"), PanPadPolicy.MAX_COVER_PERCENT)
        assertEquals(table.param("gestureInset"), PanPadPolicy.GESTURE_INSET_DP)
        assertEquals(table.param("toolTarget"), TouchTargetFloor.TOUCH_DP)
    }

    @Test
    fun variant_should_BeTheGrid_UntilItWouldCoverMoreThanTheTableShare() {
        val gridArea = table.param("padGridCells") * table.param("padCell") * table.param("padCell")
        val threshold = gridArea * HUNDRED / table.param("padMaxPct")
        val height = 200
        val width = (threshold + height - 1) / height
        assertEquals(PanVariant.Grid, PanPadPolicy.variant(width, height))
        assertEquals(PanVariant.Row, PanPadPolicy.variant(width - 1, height))
        assertEquals(PanVariant.Row, PanPadPolicy.variant(0, 0))
    }

    @Test
    fun grid_should_NeverCoverMoreThanTheShare_OverGeneratedCanvases() {
        val limitTenths = table.param("padMaxPct") * TENTHS
        for (w in WIDTHS) for (h in HEIGHTS) {
            val v = PanPadPolicy.variant(w, h)
            if (v == PanVariant.Grid) assertTrue(PanPadPolicy.coverageTenthsPercent(v, w, h) <= limitTenths, "$w x $h")
        }
    }

    @Test
    fun theRowOfFour_should_FitTheWidthOfEveryGuaranteedWindow() {
        val rowWidth = table.param("padRow") * table.param("padCell")
        for (window in table.windows.filter { it.guaranteed }) {
            assertTrue(rowWidth <= window.widthDp, "the row of four fits ${window.name}")
        }
    }

    @Test
    fun buttonShown_should_FollowTheSetting_AndAutoNeedsFingerDrawingAndTouch() {
        assertTrue(PanPadPolicy.buttonShown(PanArrows.Always, fingerDrawing = false, touchOrPenPresent = false))
        assertTrue(!PanPadPolicy.buttonShown(PanArrows.Never, fingerDrawing = true, touchOrPenPresent = true))
        assertTrue(PanPadPolicy.buttonShown(PanArrows.Auto, fingerDrawing = true, touchOrPenPresent = true))
        assertTrue(!PanPadPolicy.buttonShown(PanArrows.Auto, fingerDrawing = true, touchOrPenPresent = false), "a mouse-only desktop shows nothing")
        assertTrue(!PanPadPolicy.buttonShown(PanArrows.Auto, fingerDrawing = false, touchOrPenPresent = true))
    }

    @Test
    fun stripInset_should_BeTheGestureInsetPlusOneTouchTarget_OnlyWhileAStripIsActive() {
        assertEquals(table.param("gestureInset") + table.param("toolTarget"), PanPadPolicy.stripInsetDp(true))
        assertEquals(0, PanPadPolicy.stripInsetDp(false))
    }

    @Test
    fun oneTap_should_MoveAQuarterOfTheVisibleCanvas_TheContentTheOtherWay() {
        assertEquals(-200.0 to 0.0, PanPadPolicy.contentShift(PanDirection.Right, 1, 800.0, 600.0))
        assertEquals(200.0 to 0.0, PanPadPolicy.contentShift(PanDirection.Left, 1, 800.0, 600.0))
        assertEquals(0.0 to 150.0, PanPadPolicy.contentShift(PanDirection.Up, 1, 800.0, 600.0))
    }

    @Test
    fun doubleTap_should_MoveThreeQuartersInAll_AndOnlyOnTheSameArrowInsideTheWindow() {
        val tracker = PanTapTracker()
        val quarters = listOf(
            tracker.onTap(PanDirection.Right, 0),
            tracker.onTap(PanDirection.Right, PanPadPolicy.DOUBLE_TAP_MS),
            tracker.onTap(PanDirection.Right, PanPadPolicy.DOUBLE_TAP_MS + 1),
            tracker.onTap(PanDirection.Left, PanPadPolicy.DOUBLE_TAP_MS + 2),
        )
        assertEquals(listOf(1, 2, 1, 1), quarters)
        assertEquals(PanPadPolicy.STEP_DENOMINATOR - 1, quarters[0] + quarters[1])
    }

    @Test
    fun holdingARepeat_should_StartAt400ms_ThenEvery150ms() {
        assertEquals(0, PanPadPolicy.repeatsAfter(PanPadPolicy.FIRST_REPEAT_MS - 1))
        assertEquals(1, PanPadPolicy.repeatsAfter(PanPadPolicy.FIRST_REPEAT_MS))
        assertEquals(1, PanPadPolicy.repeatsAfter(PanPadPolicy.FIRST_REPEAT_MS + PanPadPolicy.REPEAT_MS - 1))
        assertEquals(2, PanPadPolicy.repeatsAfter(PanPadPolicy.FIRST_REPEAT_MS + PanPadPolicy.REPEAT_MS))
        assertEquals(PanPadPolicy.FIRST_REPEAT_MS, 400L)
        assertEquals(PanPadPolicy.REPEAT_MS, 150L)
    }

    @Test
    fun sheetSize_should_CycleHalfFullHalf_And_ArrowKeysResize() {
        assertEquals(SheetSize.Full, SheetSizing.cycle(SheetSize.Half))
        assertEquals(SheetSize.Half, SheetSizing.cycle(SheetSize.Full))
        assertEquals(SheetSize.Full, SheetSizing.forArrow(SheetSize.Half, VerticalArrow.Up))
        assertEquals(SheetSize.Half, SheetSizing.forArrow(SheetSize.Full, VerticalArrow.Down))
        assertEquals(SheetSize.Half, SheetSizing.forArrow(SheetSize.Half, VerticalArrow.Down))
        assertEquals(SheetSize.Full, SheetSizing.forArrow(SheetSize.Full, null))
        assertEquals("Expand", SheetSizing.toggleLabel(SheetSize.Half))
        assertEquals("Collapse", SheetSizing.toggleLabel(SheetSize.Full))
    }

    private companion object {
        const val HUNDRED = 100
        const val TENTHS = 10L
        val WIDTHS = listOf(100, 200, 240, 320, 360, 600, 840, 1280, 1920)
        val HEIGHTS = listOf(100, 180, 200, 216, 300, 480, 640, 800, 1080)
    }
}
