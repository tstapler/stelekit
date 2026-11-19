// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.focus.FocusRequester
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Story 3.6.8 (ux.md section 22) and section 17: where the walkthrough popover sits in the tab order, F6, and Esc row 8. */
class WalkthroughRegionsTest {
    private val present = setOf(TabStop.TopBar, TabStop.ToolStrip, TabStop.ViewCluster, TabStop.StatusLine)

    @Test
    fun popover_should_SitDirectlyAfterItsAnchorInTheTabOrder() {
        val order = KeyboardRegions.tabOrderWithPopover(present, TabStop.ToolStrip)
        val at = order.indexOf(TabItem.Popover)
        assertEquals(TabItem.Region(TabStop.ToolStrip), order[at - 1])
        assertEquals(TabItem.Region(TabStop.Canvas), order[at + 1], "Tab moves through the popover and on to the next region")
        assertEquals(1, order.count { it == TabItem.Popover })
    }

    @Test
    fun popover_should_FollowEveryAnchorOfTheThreeSteps_AndNeverBeUnreachable() {
        for (step in WalkthroughStep.entries) {
            val order = KeyboardRegions.tabOrderWithPopover(present, step.anchorStop)
            assertEquals(TabItem.Region(step.anchorStop), order[order.indexOf(TabItem.Popover) - 1], step.title)
        }
        val orphan = KeyboardRegions.tabOrderWithPopover(setOf(TabStop.TopBar), TabStop.SidePane)
        assertEquals(TabItem.Popover, orphan.last(), "an anchor that is not on screen still leaves the popover reachable")
    }

    @Test
    fun f6_should_LeaveThePopoverForTheRegionAfterTheAnchor_AndShiftF6ForTheAnchor() {
        assertEquals(TabStop.Canvas, KeyboardRegions.fromPopover(TabStop.ToolStrip, present, backward = false))
        assertEquals(TabStop.ToolStrip, KeyboardRegions.fromPopover(TabStop.ToolStrip, present, backward = true))
    }

    @Test
    fun regionFocus_should_NameTheNeighbourOnlyAmongRegionsOnScreen() {
        val canvas = FocusRequester()
        val regions = RegionFocus(mapOf(TabStop.Canvas to canvas))
        regions.register(TabStop.ToolStrip)
        regions.register(TabStop.Canvas)
        assertSame(canvas, regions.neighbour(TabStop.ToolStrip, backward = false))
        assertNull(regions.neighbour(TabStop.ToolStrip, backward = true), "no region before the strip is on screen")
        regions.unregister(TabStop.Canvas)
        assertNull(regions.neighbour(TabStop.ToolStrip, backward = false), "a retired region is never a target")
        assertFalse(regions.focus(TabStop.Canvas))
    }

    @Test
    fun anchors_should_BeTheStripTheViewClusterAndTheTopBar() {
        assertEquals(listOf(TabStop.ToolStrip, TabStop.ViewCluster, TabStop.TopBar), WalkthroughStep.entries.map { it.anchorStop })
    }

    // --- Esc order row 8 -------------------------------------------------------------------------------------------

    private fun touring() = SketchEditorState().also { it.walkthrough.start(InputKind.Mouse, grouped = true, returnFocus = WalkthroughState.CANVAS_FOCUS) }

    @Test
    fun esc_should_EndTheTour_When_NoRowAboveItApplies() {
        assertTrue(ShellEscape.walkthroughActs(touring(), emptySet()))
    }

    @Test
    fun esc_should_GoToEveryRowAboveTheTourFirst_AndTheTourBeforeEveryRowBelow() {
        val above = EscRow.entries.filter { it.rank < EscRow.Walkthrough.rank }
        for (row in above) assertFalse(ShellEscape.walkthroughActs(touring(), setOf(row)), "row ${row.rank} ${row.label} takes the press first")
        val below = EscRow.entries.filter { it.rank > EscRow.Walkthrough.rank }.toSet()
        assertTrue(ShellEscape.walkthroughActs(touring(), below), "the tour ends before a toast action, the tool, the selection or a toast")
    }

    @Test
    fun esc_should_ClosePanPadAndTextEditBeforeTheTour() {
        val state = touring()
        state.panPadOpen = true
        assertFalse(ShellEscape.walkthroughActs(state, emptySet()))
        state.panPadOpen = false
        state.textDraft = "label"
        assertFalse(ShellEscape.walkthroughActs(state, emptySet()))
        state.textDraft = null
        state.chooseTool(ToolId.Rectangle)
        assertTrue(ShellEscape.walkthroughActs(state, emptySet()), "an active tool is row 10, below the tour")
    }

    @Test
    fun esc_should_NotActOnATourThatIsNotShowing() {
        assertFalse(ShellEscape.walkthroughActs(SketchEditorState(), emptySet()))
    }

    @Test
    fun drain_should_EndTheTourAtRow8_BetweenCrosshairAndNoticeAction() {
        val rows = EscOrder.drain(setOf(EscRow.Crosshair, EscRow.Walkthrough, EscRow.NoticeAction, EscRow.Selection))
        assertEquals(listOf(EscRow.Crosshair, EscRow.Walkthrough, EscRow.NoticeAction, EscRow.Selection), rows)
    }
}
