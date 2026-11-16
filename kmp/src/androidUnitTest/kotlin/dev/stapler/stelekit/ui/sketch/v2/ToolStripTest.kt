// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 3.1.3 (UX-04, UX-05, UX-06, UX-46, UX-92, UX-118): the strip and the adaptive shell. Robolectric, so no display is
 * needed. Gradle-only: composes @Composable lambdas, which the Bazel android target cannot compile (see BUILD.bazel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1280dp-h800dp-mdpi")
class ToolStripTest {
    @get:Rule
    val rule = createComposeRule()

    private val phone = WindowDp(360, 640)
    private val desktop = WindowDp(1280, 800)
    private var constrained = 0

    private fun show(
        window: WindowDp,
        state: SketchEditorState = SketchEditorState(),
        technical: Boolean = false,
        pointer: PointerClass = PointerClass.TouchOrPen,
        status: ToolStripStatus = ToolStripStatus(),
    ): SketchEditorState {
        rule.setContent {
            SketchScreenV2(
                state, SketchShellConfig(ToolStripModel(technical), pointer, status, ToolStripActions(onConstrain = { constrained++ })),
                window = window,
            )
        }
        rule.waitForIdle()
        return state
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun dp(px: Float): Float = px / rule.density.density

    @Test
    fun dock_should_BeUsedAt360x640_And_RailAt1280x800() {
        show(phone)
        rule.onNodeWithTag(ShellTags.layout(StripPlacement.BottomDock)).assertExists()
        rule.onNodeWithTag(StripTags.STRIP).assertIsDisplayed()
        val strip = bounds(StripTags.STRIP)
        assertTrue(strip.top > bounds(ShellTags.CANVAS).top, "dock strip sits below the canvas")
    }

    @Test
    fun rail_should_BeUsedAtDesktop_WithTheStripAtTheLeft() {
        show(desktop)
        rule.onNodeWithTag(ShellTags.layout(StripPlacement.LeftRail)).assertExists()
        assertTrue(bounds(StripTags.STRIP).right <= bounds(ShellTags.CANVAS).left + 1f, "rail strip is left of the canvas")
        rule.onNodeWithTag(ShellTags.PANEL).assertExists()
    }

    @Test
    fun pinnedControls_should_BeOperableInEveryLayout() {
        var window by mutableStateOf(phone)
        val state = SketchEditorState()
        var undo = 0
        var redo = 0
        var deleted = 0
        rule.setContent {
            SketchScreenV2(
                state,
                SketchShellConfig(
                    ToolStripModel(false), PointerClass.TouchOrPen,
                    ToolStripStatus(canUndo = true, canRedo = true, selectionCount = 2),
                    ToolStripActions(onUndo = { undo++ }, onRedo = { redo++ }, onDelete = { deleted++ }),
                ),
                window = window,
            )
        }
        for ((round, w) in listOf(phone, desktop, WindowDp(800, 360)).withIndex()) {
            window = w
            rule.waitForIdle()
            rule.onNodeWithTag(StripTags.UNDO).performClick()
            rule.onNodeWithTag(StripTags.REDO).performClick()
            rule.onNodeWithTag(StripTags.DELETE).performClick()
            assertEquals(Triple(round + 1, round + 1, round + 1), Triple(undo, redo, deleted), "window $w")
        }
    }

    @Test
    fun delete_should_BeSeparatedFromRedoByAtLeast16dp_And_PinnedButtonsBe48dp() {
        show(phone, status = ToolStripStatus(selectionCount = 1))
        val redo = bounds(StripTags.REDO)
        val delete = bounds(StripTags.DELETE)
        assertTrue(dp(delete.left - redo.right) >= 16f - EPS, "gap ${dp(delete.left - redo.right)} dp")
        for (tag in listOf(StripTags.UNDO, StripTags.REDO, StripTags.DELETE)) {
            assertTrue(dp(bounds(tag).width) >= 48f - EPS && dp(bounds(tag).height) >= 48f - EPS, tag)
        }
    }

    @Test
    fun rail_should_SeparateDeleteFromRedoVertically() {
        show(desktop, status = ToolStripStatus(selectionCount = 1))
        assertTrue(dp(bounds(StripTags.DELETE).top - bounds(StripTags.REDO).bottom) >= 16f - EPS)
    }

    @Test
    fun everyStripButton_should_MeetTheTouchFloor_When_TouchOrPenIsPresent() {
        show(phone, technical = true, pointer = PointerClass.TouchOrPen, status = ToolStripStatus(selectionCount = 1))
        for (tag in stripTags(technical = true, grouped = true)) {
            val b = bounds(tag)
            assertTrue(dp(b.width) >= 44f - EPS && dp(b.height) >= 44f - EPS, "$tag is ${dp(b.width)} x ${dp(b.height)} dp")
        }
    }

    @Test
    fun floor_should_ChangeHitAreasOnly_Never_MoveAControlUnderThePointer() {
        var pointer by mutableStateOf(PointerClass.MouseOnly)
        val state = SketchEditorState()
        rule.setContent {
            SketchScreenV2(state, SketchShellConfig(ToolStripModel(true), pointer, ToolStripStatus(selectionCount = 1)), window = phone)
        }
        rule.waitForIdle()
        val before = stripTags(technical = true, grouped = true).associateWith { bounds(it) }
        pointer = PointerClass.TouchOrPen
        rule.waitForIdle()
        assertEquals(before, stripTags(technical = true, grouped = true).associateWith { bounds(it) })
    }

    private fun stripTags(technical: Boolean, grouped: Boolean): List<String> {
        val model = ToolStripModel(technical)
        val tags = mutableListOf(StripTags.UNDO, StripTags.REDO, StripTags.DELETE, StripTags.CONSTRAIN, StripTags.TOOL_LOCK)
        for (tool in model.stripTools) {
            val group = tool.group
            if (group == null || !grouped) tags += StripTags.tool(tool.id)
        }
        if (grouped) for (g in model.groups) { tags += StripTags.groupMain(g); tags += StripTags.groupFlyoutTarget(g) }
        return tags
    }

    @Test
    fun narrowStrip_should_GroupTools_And_FlyoutShouldPickAndRemember() {
        val state = show(phone)
        rule.onNodeWithTag(StripTags.groupMain(StripGroup.Shapes)).assertExists()
        rule.onNodeWithTag(StripTags.tool(ToolId.Diamond)).assertDoesNotExist()
        rule.onNodeWithTag(StripTags.groupFlyoutTarget(StripGroup.Shapes)).performClick()
        rule.onNodeWithTag(StripTags.groupItem(StripGroup.Shapes, ToolId.Diamond)).performClick()
        assertEquals(ToolId.Diamond, state.activeTool)
        state.chooseTool(ToolId.Select)
        rule.onNodeWithTag(StripTags.groupMain(StripGroup.Shapes)).performClick()
        assertEquals(ToolId.Diamond, state.activeTool, "the main target activates the group's last-used tool")
    }

    @Test
    fun tallRail_should_ShowEveryToolFlat_When_TheFlatExtentFits() {
        show(WindowDp(1920, 1080), technical = true)
        for (tool in ToolStripModel(true).stripTools) rule.onNodeWithTag(StripTags.tool(tool.id)).assertExists()
        rule.onNodeWithTag(StripTags.groupMain(StripGroup.Shapes)).assertDoesNotExist()
    }

    @Test
    fun clickingATool_should_SelectItAndMarkItSelected() {
        val state = show(desktop)
        rule.onNodeWithTag(StripTags.tool(ToolId.Rectangle)).assertIsNotSelected().performClick()
        assertEquals(ToolId.Rectangle, state.activeTool)
        rule.onNodeWithTag(StripTags.tool(ToolId.Rectangle)).assertIsSelected()
    }

    @Test
    fun toolLock_should_KeepTheToolAfterOneShape_ShowAChip_And_TheChipShouldTurnItOff() {
        val state = show(desktop)
        rule.onNodeWithTag(StripTags.tool(ToolId.Rectangle)).performClick()
        rule.onNodeWithTag(ShellTags.TOOL_LOCK_CHIP).assertDoesNotExist()
        rule.onNodeWithTag(StripTags.TOOL_LOCK).performClick()
        assertTrue(state.toolLock)
        rule.onNodeWithTag(ShellTags.TOOL_LOCK_CHIP).assertIsDisplayed()
        state.onShapeCommitted()
        assertEquals(ToolId.Rectangle, state.activeTool)
        rule.onNodeWithTag(ShellTags.TOOL_LOCK_CHIP).performClick()
        assertFalse(state.toolLock)
        state.onShapeCommitted()
        assertEquals(ToolId.Select, state.activeTool)
    }

    @Test
    fun constrain_should_AppearOnADiagramSheetOnlyWithASelection_And_BeReachableFromTheOverflowAlways() {
        show(phone)
        rule.onNodeWithTag(StripTags.CONSTRAIN).assertDoesNotExist()
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.onNodeWithTag(ShellTags.OVERFLOW_CONSTRAIN).assertExists().performClick()
        assertEquals(1, constrained)
    }

    @Test
    fun constrain_should_ShowOnASelection_And_FireItsAction() {
        show(phone, status = ToolStripStatus(selectionCount = 1))
        rule.onNodeWithTag(StripTags.CONSTRAIN).assertContentDescriptionEquals("Constrain (K)").performClick()
        assertEquals(1, constrained)
    }

    @Test
    fun constrain_should_NeverBeAbsentOnATechnicalSheet_AndSayWhyWhenNothingIsSelected() {
        show(phone, technical = true)
        rule.onNodeWithTag(StripTags.CONSTRAIN)
            .assertContentDescriptionEquals("Constrain, unavailable: Select an element to constrain").performClick()
        assertEquals(0, constrained, "a disabled Constrain does nothing")
    }

    @Test
    fun pointAndArc_should_BeInTheOverflowUnderMoreTools_OnADiagramSheet_And_InAGeometryGroupOnATechnicalSheet() {
        show(phone)
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.onNodeWithTag(ShellTags.overflowTool(ToolId.Point)).assertExists()
        rule.onNodeWithTag(ShellTags.overflowTool(ToolId.Arc)).assertExists()
    }

    @Test
    fun geometryGroup_should_HoldPointAndArc_OnATechnicalSheetAtCompactWidth() {
        show(phone, technical = true)
        rule.onNodeWithTag(StripTags.groupMain(StripGroup.Geometry)).assertExists()
        rule.onNodeWithTag(StripTags.groupFlyoutTarget(StripGroup.Geometry)).performClick()
        rule.onNodeWithTag(StripTags.groupItem(StripGroup.Geometry, ToolId.Point)).assertExists()
        rule.onNodeWithTag(StripTags.groupItem(StripGroup.Geometry, ToolId.Arc)).assertExists()
    }

    @Test
    fun blockedTool_should_StayFocusableAnnounceTheReason_And_NotChangeTheTool() {
        val state = show(desktop, status = ToolStripStatus(block = ToolBlock.ReadOnly))
        rule.onNodeWithTag(StripTags.tool(ToolId.Rectangle))
            .assertContentDescriptionEquals("Rectangle, unavailable: read-only sketch").performClick()
        assertEquals(ToolId.Select, state.activeTool)
    }

    @Test
    fun strip_should_NeverOverlapTheChipRowOrTheViewCluster_InEveryLayout() {
        var window by mutableStateOf(phone)
        val state = SketchEditorState(toolLock = true)
        rule.setContent { SketchScreenV2(state, SketchShellConfig(ToolStripModel(true), PointerClass.TouchOrPen), window = window) }
        for (w in listOf(phone, desktop, WindowDp(800, 360), WindowDp(320, 480))) {
            window = w
            rule.waitForIdle()
            val strip = bounds(StripTags.STRIP)
            for (other in listOf(ShellTags.CHIP_ROW, ShellTags.VIEW_CLUSTER)) {
                assertFalse(strip.overlaps(bounds(other)), "$w: strip overlaps $other")
            }
            assertFalse(bounds(ShellTags.CHIP_ROW).overlaps(bounds(ShellTags.VIEW_CLUSTER)), "$w: chip row meets the view cluster")
        }
    }

    @Test
    fun viewCluster_should_KeepClearOfTheHostsBottomEndButton() {
        val state = SketchEditorState()
        val mic = 72
        rule.setContent {
            SketchScreenV2(
                state, SketchShellConfig(ToolStripModel(false), PointerClass.TouchOrPen, endInset = androidx.compose.ui.unit.Dp(mic.toFloat())),
                window = phone,
            )
        }
        rule.waitForIdle()
        val canvas = bounds(ShellTags.CANVAS)
        assertTrue(canvas.right - bounds(ShellTags.VIEW_CLUSTER).right >= mic * rule.density.density - 1f)
    }

    @Test
    fun zoomButtons_should_ChangeTheViewport() {
        val state = show(phone)
        val before = state.viewport.zoom
        rule.onNodeWithTag("sketch.zoomin").performClick()
        assertTrue(state.viewport.zoom > before)
        rule.onNodeWithContentDescription("Zoom out").performClick()
        assertEquals(before, state.viewport.zoom, 1e-9)
    }

    private fun Rect.overlaps(o: Rect): Boolean = left < o.right && right > o.left && top < o.bottom && bottom > o.top

    private companion object {
        const val EPS = 0.6f
    }
}
