// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.PanelHost
import dev.stapler.stelekit.ui.sketch.v2.PointerClass
import dev.stapler.stelekit.ui.sketch.v2.ShellLayout
import dev.stapler.stelekit.ui.sketch.v2.StripGroup
import dev.stapler.stelekit.ui.sketch.v2.StripPlacement
import dev.stapler.stelekit.ui.sketch.v2.ToolBlock
import dev.stapler.stelekit.ui.sketch.v2.ToolLockPolicy
import dev.stapler.stelekit.ui.sketch.v2.ToolStripModel
import dev.stapler.stelekit.ui.sketch.v2.TouchTargetFloor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 3.1.3: strip contents, layout choice on both axes, the one touch-target floor, tool lock (UX-04, UX-06, UX-46, UX-92, UX-118). */
class ToolStripModelTest {
    @Test
    fun layout_should_BeDockAtPhoneAndRailAtDesktop_When_WindowsOfTheAcceptanceCriteria() {
        assertEquals(ShellLayout(StripPlacement.BottomDock, PanelHost.BottomSheet), ShellLayout.choose(360, 640))
        assertEquals(ShellLayout(StripPlacement.LeftRail, PanelHost.PersistentPane), ShellLayout.choose(1280, 800))
    }

    @Test
    fun layout_should_UseRailAndSideSheet_When_HeightIsCompactWhateverTheWidth() {
        for (width in listOf(320, 600, 800, 1280)) {
            assertEquals(ShellLayout(StripPlacement.LeftRail, PanelHost.SideSheet), ShellLayout.choose(width, 360), "width $width")
        }
    }

    @Test
    fun mediumWidthAtRegularHeight_should_BeTheDock() {
        assertEquals(StripPlacement.BottomDock, ShellLayout.choose(600, 800).placement)
    }

    @Test
    fun floor_should_Be44OnTouchOrPenAnd24OnMouseOnly_And_PinnedControlsAre48() {
        assertEquals(44, TouchTargetFloor.floorDp(PointerClass.TouchOrPen))
        assertEquals(24, TouchTargetFloor.floorDp(PointerClass.MouseOnly))
        assertEquals(48, TouchTargetFloor.pinnedDp())
        assertTrue(TouchTargetFloor.meets(PointerClass.TouchOrPen, 44f))
        assertFalse(TouchTargetFloor.meets(PointerClass.TouchOrPen, 43f))
        assertTrue(TouchTargetFloor.meets(PointerClass.MouseOnly, 24f))
    }

    @Test
    fun handles_should_HaveAtLeast44HitAreaOnTouch_While_TheVisibleHandleStays8() {
        assertTrue(TouchTargetFloor.handleHitDp(PointerClass.TouchOrPen, largerTouchHandles = true) >= 44)
        assertEquals(44, TouchTargetFloor.handleHitDp(PointerClass.TouchOrPen, largerTouchHandles = false))
        assertEquals(24, TouchTargetFloor.handleHitDp(PointerClass.MouseOnly, largerTouchHandles = true))
        assertEquals(8, TouchTargetFloor.VISIBLE_HANDLE_DP)
        assertEquals(22, TouchTargetFloor.PART_HIT_TOUCH_TOLERANCE_DP)
        assertTrue(TouchTargetFloor.largerHandlesDefault(touchPrimary = true))
        assertFalse(TouchTargetFloor.largerHandlesDefault(touchPrimary = false))
    }

    @Test
    fun deleteGap_should_SeparateDeleteFromRedoByAtLeast16() {
        assertTrue(TouchTargetFloor.DELETE_GAP_DP >= 16)
    }

    @Test
    fun technicalSheet_should_ListAll14ToolsInThreeGroups_And_DiagramSheetMovesPointAndArcToOverflow() {
        val technical = ToolStripModel(technicalSheet = true)
        assertEquals(ToolId.entries.size, technical.stripTools.size)
        assertEquals(listOf(StripGroup.Shapes, StripGroup.Lines, StripGroup.Geometry), technical.groups)
        assertTrue(technical.overflowTools.isEmpty())
        val diagram = ToolStripModel(technicalSheet = false)
        assertEquals(listOf(ToolId.Point, ToolId.Arc), diagram.overflowTools.map { it.id })
        assertEquals(listOf(StripGroup.Shapes, StripGroup.Lines), diagram.groups)
        assertEquals(ToolId.entries.size - 2, diagram.stripTools.size)
    }

    @Test
    fun shortcuts_should_ComeFromTheKeymap() {
        val model = ToolStripModel(technicalSheet = true)
        assertEquals("R", model.tool(ToolId.Rectangle).shortcut)
        assertEquals("V", model.tool(ToolId.Select).shortcut)
        assertEquals("X", model.tool(ToolId.Point).shortcut)
        assertEquals("Rectangle (R)", model.description(model.tool(ToolId.Rectangle), null))
    }

    @Test
    fun blockedTool_should_AnnounceWhyItIsUnavailable() {
        val model = ToolStripModel(technicalSheet = false)
        assertEquals("Rectangle, unavailable: read-only sketch", model.description(model.tool(ToolId.Rectangle), ToolBlock.ReadOnly))
        assertEquals("Rectangle, unavailable: locked layer: Walls", model.description(model.tool(ToolId.Rectangle), ToolBlock.LayerLocked("Walls")))
    }

    @Test
    fun stripShouldBeFlatWhenTheFlatExtentFits_And_GroupedOtherwise() {
        val model = ToolStripModel(technicalSheet = true)
        assertTrue(model.groupedExtentDp() < model.flatExtentDp())
        assertTrue(model.isFlat(model.flatExtentDp()))
        assertFalse(model.isFlat(model.flatExtentDp() - 1))
        assertFalse(model.isFlat(360))
        assertTrue(model.isFlat(1280))
    }

    @Test
    fun toolLock_should_KeepTheToolAfterOneShape_And_OtherwiseReturnToSelect() {
        assertEquals(ToolId.Rectangle, ToolLockPolicy.toolAfterCommit(ToolId.Rectangle, locked = true))
        assertEquals(ToolId.Select, ToolLockPolicy.toolAfterCommit(ToolId.Rectangle, locked = false))
        assertEquals(ToolId.Hand, ToolLockPolicy.toolAfterCommit(ToolId.Hand, locked = false))
    }
}
