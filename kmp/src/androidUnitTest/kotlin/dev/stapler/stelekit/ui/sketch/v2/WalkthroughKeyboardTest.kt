// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ux.md sections 10, 17 and 22 (Story 3.6.8, Task 3.6.8e): the walkthrough popover is a region in the tab order directly after
 * its anchor, F6 and Shift+F6 leave it for a region with the tour still shown, and Esc ends it only when no row above it in the
 * one Esc order applies. Robolectric; Gradle-only like the other Compose host tests (see BUILD.bazel). It asserts focus moves
 * in a compose host; it does not claim what a screen reader announces.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1280dp-h800dp-mdpi")
class WalkthroughKeyboardTest {
    @get:Rule
    val rule = createComposeRule()

    private var hostRows: Set<EscRow> = emptySet()

    private fun show(): SketchEditorState {
        val state = SketchEditorState()
        rule.setContent {
            SketchScreenV2(state, SketchShellConfig(ToolStripModel(true), PointerClass.MouseOnly, escRows = { hostRows }), window = WindowDp(1280, 800))
        }
        rule.waitForIdle()
        return state
    }

    private fun start(state: SketchEditorState, steps: Int = 0) {
        rule.runOnIdle { state.walkthrough.start(InputKind.Keyboard, grouped = false, returnFocus = WalkthroughState.CANVAS_FOCUS) }
        rule.waitForIdle()
        repeat(steps) {
            rule.onNodeWithTag(WalkthroughTags.NEXT).performClick()
            rule.waitForIdle()
        }
    }

    private fun shiftF6() = rule.onNodeWithTag(WalkthroughTags.POPOVER).performKeyInput {
        keyDown(Key.ShiftLeft)
        pressKey(Key.F6)
        keyUp(Key.ShiftLeft)
    }

    @Test
    fun tab_should_MoveFromTheAnchorIntoThePopover_ThenOnToTheNextRegion_WithoutEndingTheTour() {
        val state = show()
        start(state, steps = 2)
        assertEquals(WalkthroughStep.Shortcuts, state.walkthrough.step)
        rule.onNodeWithTag(ShellTags.OVERFLOW).requestFocus()
        rule.onNodeWithTag(ShellTags.OVERFLOW).performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()
        rule.onNodeWithTag(WalkthroughTags.POPOVER).assertIsFocused()

        rule.onNodeWithTag(WalkthroughTags.DONE).requestFocus()
        rule.onNodeWithTag(WalkthroughTags.DONE).performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()
        assertTrue(state.walkthrough.active, "Tab leaves the popover without ending the tour or wrapping")
        val strip = rule.onNodeWithTag(StripTags.STRIP).fetchSemanticsNode().boundsInRoot
        val focused = rule.onAllNodes(isFocused()).fetchSemanticsNodes().single().boundsInRoot
        assertTrue(strip.contains(focused.center), "focus moved to the region after the top bar, the tool strip")
    }

    @Test
    fun f6_should_LeaveThePopoverForTheRegionAfterTheAnchor_AndShiftF6ForTheAnchor_WithTheTourStillShown() {
        val state = show()
        start(state)
        assertEquals(WalkthroughStep.Tools, state.walkthrough.step)
        rule.onNodeWithTag(WalkthroughTags.POPOVER).requestFocus()
        rule.onNodeWithTag(WalkthroughTags.POPOVER).performKeyInput { pressKey(Key.F6) }
        rule.waitForIdle()
        assertTrue(state.walkthrough.active)
        rule.onNodeWithTag(ShellTags.CANVAS).assertIsFocused()

        start(state)
        rule.onNodeWithTag(WalkthroughTags.POPOVER).requestFocus()
        shiftF6()
        rule.waitForIdle()
        assertTrue(state.walkthrough.active)
        val strip = rule.onNodeWithTag(StripTags.STRIP).fetchSemanticsNode().boundsInRoot
        val focused = rule.onAllNodes(isFocused()).fetchSemanticsNodes().single().boundsInRoot
        assertTrue(strip.contains(focused.center), "Shift+F6 goes to the anchor, the tool strip")
    }

    @Test
    fun esc_should_EndTheTourFromThePopover_OnlyWhenNoRowAboveItApplies() {
        val state = show()
        start(state)
        hostRows = setOf(EscRow.Solving)
        rule.onNodeWithTag(WalkthroughTags.POPOVER).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertTrue(state.walkthrough.active, "row 4 (Solving...) takes the first Esc")

        hostRows = setOf(EscRow.NoticeAction, EscRow.Selection)
        rule.onNodeWithTag(WalkthroughTags.POPOVER).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertFalse(state.walkthrough.active, "rows 9 to 12 come after row 8: the tour ends first")
    }

    @Test
    fun esc_should_ClosePanPadBeforeTheTour_FromAnywhere() {
        val state = show()
        rule.runOnIdle { state.panPadOpen = true }
        start(state)
        rule.onNodeWithTag(ShellTags.CANVAS).requestFocus()
        rule.onNodeWithTag(ShellTags.CANVAS).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertTrue(state.walkthrough.active, "the Pan pad is row 2 and keeps the tour alive")
        rule.runOnIdle { state.panPadOpen = false }
        rule.onNodeWithTag(ShellTags.CANVAS).requestFocus()
        rule.onNodeWithTag(ShellTags.CANVAS).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertFalse(state.walkthrough.active)
    }
}
