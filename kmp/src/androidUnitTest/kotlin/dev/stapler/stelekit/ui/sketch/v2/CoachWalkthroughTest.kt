// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.click
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 3.6.8e (UX-97, ux.md section 22): the three-step coach walkthrough: indicator and controls, focus and announcement
 * order, Tab that never traps, Esc from anywhere, a popover that never covers its anchor, and a tour that changes nothing in
 * the sketch. Robolectric; Gradle-only like the other Compose host tests (see BUILD.bazel).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1280dp-h800dp-mdpi")
class CoachWalkthroughTest {
    @get:Rule
    val rule = createComposeRule()

    private val phone = WindowDp(360, 640)
    private val largeDesktop = WindowDp(1920, 1080)

    private fun show(window: WindowDp = phone, state: SketchEditorState = SketchEditorState(), banner: BannerSlotState? = null): SketchEditorState {
        rule.setContent { SketchScreenV2(state, SketchShellConfig(ToolStripModel(true), PointerClass.TouchOrPen, banner = banner), window = window) }
        rule.waitForIdle()
        return state
    }

    private fun start(state: SketchEditorState, input: InputKind = InputKind.Mouse, grouped: Boolean = true, returnFocus: String = WalkthroughState.CANVAS_FOCUS) {
        rule.runOnIdle { state.walkthrough.start(input, grouped, returnFocus) }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun Rect.overlapsRect(o: Rect): Boolean = left < o.right && right > o.left && top < o.bottom && bottom > o.top

    private fun next() = rule.onNodeWithTag(WalkthroughTags.NEXT).performClick().also { rule.waitForIdle() }

    // ---- the steps --------------------------------------------------------------------------------------------------

    @Test
    fun stepOne_should_ShowTheIndicator_TheTitleAndABackButtonThatIsDisabled() {
        val state = show()
        start(state)
        rule.onNodeWithTag(WalkthroughTags.POPOVER).assertIsDisplayed()
        rule.onNodeWithTag(WalkthroughTags.INDICATOR).assertTextEquals("Step 1 of 3")
        rule.onNodeWithTag(WalkthroughTags.BACK).assertIsNotEnabled()
        rule.onNodeWithTag(WalkthroughTags.NEXT).assertIsEnabled()
        rule.onNodeWithTag(WalkthroughTags.DONE).assertDoesNotExist()
    }

    @Test
    fun theTour_should_WalkThreeStepsAndEndWithDone() {
        val state = show()
        start(state)
        next()
        rule.onNodeWithTag(WalkthroughTags.INDICATOR).assertTextEquals("Step 2 of 3")
        rule.onNodeWithTag(WalkthroughTags.BACK).assertIsEnabled()
        next()
        rule.onNodeWithTag(WalkthroughTags.INDICATOR).assertTextEquals("Step 3 of 3")
        rule.onNodeWithTag(WalkthroughTags.NEXT).assertDoesNotExist()
        rule.onNodeWithTag(WalkthroughTags.DONE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(WalkthroughTags.POPOVER).assertDoesNotExist()
        assertFalse(state.walkthrough.active)
    }

    @Test
    fun back_should_ReturnToThePreviousStep() {
        val state = show()
        start(state)
        next()
        rule.onNodeWithTag(WalkthroughTags.BACK).performClick()
        rule.onNodeWithTag(WalkthroughTags.INDICATOR).assertTextEquals("Step 1 of 3")
        assertEquals(WalkthroughStep.Tools, state.walkthrough.step)
    }

    // ---- focus and announcement ----------------------------------------------------------------------------------------

    @Test
    fun focus_should_GoOnceToThePopoverOnStart_AndToThePrimaryButtonOnEveryLaterStep() {
        val state = show()
        start(state)
        rule.onNodeWithTag(WalkthroughTags.POPOVER).assertIsFocused()
        next()
        rule.onNodeWithTag(WalkthroughTags.NEXT).assertIsFocused()
        next()
        rule.onNodeWithTag(WalkthroughTags.DONE).assertIsFocused()
    }

    @Test
    fun stepOne_should_BeAnnouncedByThePopoversName_NotByALiveRegion() {
        val state = show()
        start(state, InputKind.TouchOrPen)
        val expected = WalkthroughCopy.announcement(WalkthroughStep.Tools, InputKind.TouchOrPen, true)
        rule.onNodeWithTag(WalkthroughTags.POPOVER).assertContentDescriptionEquals(expected)
        val text = rule.onNodeWithTag(WalkthroughTags.TEXT).fetchSemanticsNode().config
        assertFalse(SemanticsProperties.LiveRegion in text, "step 1 sends no separate live announcement")
    }

    @Test
    fun laterSteps_should_SendExactlyOnePoliteString_TheStepIndicatorTitleAndSentence() {
        val state = show()
        start(state, InputKind.Mouse)
        next()
        val text = rule.onNodeWithTag(WalkthroughTags.TEXT).fetchSemanticsNode().config
        assertTrue(SemanticsProperties.LiveRegion in text)
        assertEquals(WalkthroughCopy.announcement(WalkthroughStep.Zoom, InputKind.Mouse, true), text[SemanticsProperties.ContentDescription].single())
        val live = rule.onAllNodesLive()
        assertEquals(1, live, "the walkthrough owns one live region at a time")
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesLive(): Int =
        onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion), useUnmergedTree = true).fetchSemanticsNodes().count { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("sketch.walkthrough") == true
        }

    // ---- keys -------------------------------------------------------------------------------------------------------

    @Test
    fun escape_should_EndTheTour_FromThePopover() {
        val state = show()
        start(state)
        rule.onNodeWithTag(WalkthroughTags.POPOVER).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertFalse(state.walkthrough.active)
        rule.onNodeWithTag(WalkthroughTags.POPOVER).assertDoesNotExist()
    }

    @Test
    fun escape_should_EndTheTour_FromAnywhereElseWhileItIsShown() {
        val state = show()
        start(state)
        rule.onNodeWithTag(ShellTags.CANVAS).requestFocus()
        rule.onNodeWithTag(ShellTags.CANVAS).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertFalse(state.walkthrough.active)
    }

    @Test
    fun escape_should_NotEndATourThatIsNotShown_AndTabShouldNotTrapFocusInThePopover() {
        val state = show()
        rule.onNodeWithTag(ShellTags.CANVAS).requestFocus()
        rule.onNodeWithTag(ShellTags.CANVAS).performKeyInput { pressKey(Key.Escape) }
        assertFalse(state.walkthrough.active)

        start(state)
        next()
        next()
        rule.onNodeWithTag(WalkthroughTags.DONE).assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()
        rule.onNodeWithTag(WalkthroughTags.DONE).assertIsNotFocused()
        assertTrue(state.walkthrough.active, "Tab moves on without ending or wrapping inside the popover")
    }

    @Test
    fun keyboardVariant_should_ShowEscToFinish_AndTouchAndMouseVariantsShouldNot() {
        val keyboard = show()
        start(keyboard, InputKind.Keyboard)
        rule.onNodeWithTag(WalkthroughTags.ESC_HINT).assertTextEquals(SketchStrings.Walkthrough.ESC_TO_FINISH)
        assertEquals(SketchStrings.Walkthrough.TOOLS_GROUPED_KEYBOARD, rule.onNodeWithTag(WalkthroughTags.TEXT).fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
    }

    @Test
    fun touchVariant_should_NameNoKey_AndShowNoEscHint() {
        val state = show()
        start(state, InputKind.TouchOrPen)
        rule.onNodeWithTag(WalkthroughTags.ESC_HINT).assertDoesNotExist()
        rule.onNodeWithTag(WalkthroughTags.TEXT).assertTextEquals(SketchStrings.Walkthrough.TOOLS_GROUPED_TOUCH)
    }

    @Test
    fun stepOne_should_UseTheFlatText_When_TheStripHasNoChevrons() {
        val state = show(largeDesktop)
        start(state, InputKind.Mouse, grouped = false)
        rule.onNodeWithTag(WalkthroughTags.TEXT).assertTextEquals(SketchStrings.Walkthrough.TOOLS_FLAT_POINTER)
    }

    // ---- placement, ring and what it leaves alone ---------------------------------------------------------------------

    @Test
    fun theRing_should_SitOnTheAnchor_AndThePopoverShouldNeverCoverItsAnchor_InEveryStep() {
        val state = show()
        start(state)
        val anchors = listOf(StripTags.STRIP, ShellTags.VIEW_CLUSTER, ShellTags.OVERFLOW)
        for (anchor in anchors) {
            val ring = bounds(WalkthroughTags.RING)
            val target = bounds(anchor)
            assertTrue(kotlin.math.abs(ring.left - target.left) < 1f && kotlin.math.abs(ring.top - target.top) < 1f, "the ring sits on $anchor: $ring vs $target")
            assertFalse(bounds(WalkthroughTags.POPOVER).overlapsRect(target), "the popover covers $anchor")
            if (anchor != anchors.last()) next()
        }
    }

    @Test
    fun theTour_should_ChangeNothingInTheSketchState() {
        val state = show()
        state.chooseTool(ToolId.Ellipse)
        val before = Triple(state.activeTool, state.selection, state.viewport)
        start(state)
        next()
        next()
        rule.onNodeWithTag(WalkthroughTags.DONE).performClick()
        rule.waitForIdle()
        assertEquals(before, Triple(state.activeTool, state.selection, state.viewport))
    }

    // ---- ending ---------------------------------------------------------------------------------------------------------

    @Test
    fun activatingTheAnchoredControl_should_RunItsActionAndEndTheTour() {
        val state = show()
        start(state)
        rule.onNodeWithTag(StripTags.tool(ToolId.Select)).performClick()
        rule.waitForIdle()
        assertFalse(state.walkthrough.active, "the strip is step 1's anchor")
        assertEquals(ToolId.Select, state.activeTool)

        start(state)
        next()
        val zoom = state.viewport.zoom
        rule.onNodeWithTag("sketch.zoomin").performClick()
        rule.waitForIdle()
        assertFalse(state.walkthrough.active, "the view cluster is step 2's anchor")
        assertTrue(state.viewport.zoom > zoom, "the button still zoomed")
    }

    @Test
    fun anotherControl_should_NotEndTheTour() {
        val state = show()
        start(state)
        rule.onNodeWithTag("sketch.zoomin").performClick()
        assertTrue(state.walkthrough.active, "the zoom buttons are not step 1's anchor")
    }

    @Test
    fun showTips_should_StartTheTourFromTheOverflowMenu_AndFocusShouldReturnToTheOverflowButton() {
        val state = show()
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.onNodeWithTag(ShellTags.OVERFLOW_TIPS).performClick()
        rule.waitForIdle()
        assertEquals(WalkthroughStep.Tools, state.walkthrough.step)
        rule.onNodeWithTag(WalkthroughTags.POPOVER).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertFalse(state.walkthrough.active)
        rule.onNodeWithTag(ShellTags.OVERFLOW).assertIsFocused()
    }

    @Test
    fun showTips_should_RetireAVisibleCoachMark() {
        val banner = BannerSlotState(SketchNoticeQueue(sequencer = OnboardingSequencer(OnboardingCounters(InMemorySettings()))))
        show(banner = banner)
        rule.onNodeWithTag(CoachMarkTags.ROW).assertIsDisplayed()
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.onNodeWithTag(ShellTags.OVERFLOW_TIPS).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(CoachMarkTags.ROW).assertDoesNotExist()
        assertNull(banner.head)
    }

    @Test
    fun theOverflowMenu_should_ListKeyboardShortcuts_AndShowTips() {
        val state = show()
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.onNodeWithTag(ShellTags.OVERFLOW_SHORTCUTS).assertIsDisplayed()
        rule.onNodeWithTag(ShellTags.OVERFLOW_TIPS).assertIsDisplayed()
        assertNotNull(state)
    }

    @Test
    fun theOverflowButton_should_EndTheTourAtStepThree_WhenItIsActivated() {
        val state = show()
        start(state)
        next()
        next()
        rule.onNodeWithTag(ShellTags.OVERFLOW).performTouchInput { click() }
        rule.waitForIdle()
        assertFalse(state.walkthrough.active, "the overflow button is step 3's anchor")
    }
}
