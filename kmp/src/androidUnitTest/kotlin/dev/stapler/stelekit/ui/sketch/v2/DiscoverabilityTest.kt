// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.requestFocus
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 3.6.8, Tasks 3.6.8a and 3.6.8b (UX-97, UX-109, UX-124; D-11): every tool is findable without a hidden gesture, the
 * first-run coach mark is offered once per device, and the hint line follows the input device. Robolectric; Gradle-only like
 * the other Compose host tests (see BUILD.bazel).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1280dp-h800dp-mdpi")
class DiscoverabilityTest {
    @get:Rule
    val rule = createComposeRule()

    private val phone = WindowDp(360, 640)
    private val largeDesktop = WindowDp(1920, 1080)
    private val desktop = WindowDp(1280, 800)

    private fun show(window: WindowDp, state: SketchEditorState = SketchEditorState(), config: SketchShellConfig = SketchShellConfig(ToolStripModel(true), PointerClass.TouchOrPen)): SketchEditorState {
        rule.setContent { SketchScreenV2(state, config, window = window) }
        rule.waitForIdle()
        return state
    }

    private fun bannerSlot(settings: InMemorySettings = InMemorySettings()) =
        BannerSlotState(SketchNoticeQueue(sequencer = OnboardingSequencer(OnboardingCounters(settings))))

    // ---- flat tools and the flyout (Task 3.6.8a) --------------------------------------------------------------------

    @Test
    fun allFourteenTools_should_BeShownFlat_When_TheFlatExtentFits() {
        show(largeDesktop)
        for (tool in ToolId.entries) rule.onNodeWithTag(StripTags.tool(tool)).assertExists()
        for (group in StripGroup.entries) rule.onNodeWithTag(StripTags.groupFlyoutTarget(group)).assertDoesNotExist()
    }

    @Test
    fun everyGroup_should_BeASplitButton_WithAMoreToolsFlyoutTarget_When_TheStripIsTight() {
        show(desktop)
        for (group in StripGroup.entries) {
            rule.onNodeWithTag(StripTags.groupMain(group)).assertExists()
            rule.onNodeWithTag(StripTags.groupFlyoutTarget(group)).assertExists().assertContentDescriptionEquals(SketchStrings.Strip.flyoutDescription(group.label))
        }
    }

    private fun flyoutOpen(group: StripGroup) =
        rule.onAllNodesWithTagCount(StripTags.groupItem(group, group.tools.first())) > 0

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun theFlyout_should_OpenFromItsOwnTarget() {
        show(desktop)
        rule.onNodeWithTag(StripTags.groupFlyoutTarget(StripGroup.Lines)).performClick()
        assertTrue(flyoutOpen(StripGroup.Lines))
    }

    @Test
    fun theFlyout_should_OpenByLongPress_OnTouch() {
        show(desktop)
        rule.onNodeWithTag(StripTags.groupMain(StripGroup.Lines)).performTouchInput { longClick() }
        assertTrue(flyoutOpen(StripGroup.Lines), "a long press on a group's main target opens its flyout")
    }

    @Test
    fun theFlyout_should_OpenByRightClick_OrTheBarrelButton() {
        show(desktop)
        rule.onNodeWithTag(StripTags.groupMain(StripGroup.Shapes)).performMouseInput { rightClick() }
        assertTrue(flyoutOpen(StripGroup.Shapes))
    }

    @Test
    fun theFlyout_should_OpenByDownArrow_AndByTheContextMenuKey_FromTheKeyboard() {
        show(desktop)
        rule.onNodeWithTag(StripTags.groupMain(StripGroup.Shapes)).requestFocus()
        rule.onNodeWithTag(StripTags.groupMain(StripGroup.Shapes)).assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        assertTrue(flyoutOpen(StripGroup.Shapes), "Down opens the flyout")
        rule.onNodeWithTag(StripTags.groupItem(StripGroup.Shapes, ToolId.Ellipse)).performClick()
        assertTrue(!flyoutOpen(StripGroup.Shapes))
        rule.onNodeWithTag(StripTags.groupMain(StripGroup.Lines)).requestFocus()
        rule.onNodeWithTag(StripTags.groupMain(StripGroup.Lines)).performKeyInput { pressKey(Key.Menu) }
        assertTrue(flyoutOpen(StripGroup.Lines), "the context-menu key opens the flyout")
    }

    @Test
    fun enterOnAGroup_should_ActivateItsLastUsedTool_AndNotOpenTheFlyout() {
        val state = show(desktop)
        state.chooseTool(ToolId.Diamond)
        state.chooseTool(ToolId.Select)
        rule.onNodeWithTag(StripTags.groupMain(StripGroup.Shapes)).requestFocus()
        rule.onNodeWithTag(StripTags.groupMain(StripGroup.Shapes)).performKeyInput { pressKey(Key.Enter) }
        assertEquals(ToolId.Diamond, state.activeTool)
        assertTrue(!flyoutOpen(StripGroup.Shapes))
    }

    @Test
    fun pickingFromTheFlyout_should_ChooseTheTool_CloseTheFlyout_AndReturnFocusToTheGroup() {
        val state = show(desktop)
        rule.onNodeWithTag(StripTags.groupFlyoutTarget(StripGroup.Lines)).performClick()
        rule.onNodeWithTag(StripTags.groupItem(StripGroup.Lines, ToolId.Freedraw)).performClick()
        rule.waitForIdle()
        assertEquals(ToolId.Freedraw, state.activeTool)
        assertTrue(!flyoutOpen(StripGroup.Lines))
        rule.onNodeWithTag(StripTags.groupMain(StripGroup.Lines)).assertIsFocused()
    }

    @Test
    fun aLongPress_should_StillShowHelp_OnAButtonThatHasNoFlyout() {
        val state = show(desktop)
        rule.onNodeWithTag(StripTags.tool(ToolId.Text)).performTouchInput { longClick() }
        assertTrue(state.help.shown != null)
    }

    // ---- the coach mark (Task 3.6.8b) -------------------------------------------------------------------------------

    @Test
    fun coachMark_should_ShowOnTheFirstOpen_And_StayRetiredOnTheNextOpenOfTheSameDevice() {
        val settings = InMemorySettings()
        var current by mutableStateOf(bannerSlot(settings))
        rule.setContent { SketchScreenV2(SketchEditorState(), SketchShellConfig(ToolStripModel(true), PointerClass.TouchOrPen, banner = current), window = phone) }
        rule.waitForIdle()
        rule.onNodeWithTag(CoachMarkTags.ROW).assertIsDisplayed()
        rule.onNodeWithTag(CoachMarkTags.GOT_IT).performClick()
        rule.onNodeWithTag(CoachMarkTags.ROW).assertDoesNotExist()

        current = bannerSlot(settings)
        rule.waitForIdle()
        rule.onNodeWithTag(CoachMarkTags.ROW).assertDoesNotExist()
    }

    @Test
    fun coachMark_should_ShowItsFixedTextAndStartTheWalkthrough_WhenShowMeIsPressed() {
        val state = show(phone, config = SketchShellConfig(ToolStripModel(true), PointerClass.TouchOrPen, banner = bannerSlot()))
        rule.onNodeWithTag(CoachMarkTags.ROW).assertIsDisplayed()
        rule.onNodeWithTag(CoachMarkTags.SHOW_ME).performClick()
        rule.onNodeWithTag(CoachMarkTags.ROW).assertDoesNotExist()
        rule.onNodeWithTag(WalkthroughTags.POPOVER).assertIsDisplayed()
        assertEquals(WalkthroughStep.Tools, state.walkthrough.step)
    }

    @Test
    fun coachMark_should_BeAnnouncedAsAPoliteLiveRegion_AndOperableByKeyboard() {
        show(phone, config = SketchShellConfig(ToolStripModel(true), PointerClass.TouchOrPen, banner = bannerSlot()))
        rule.onNodeWithTag(CoachMarkTags.SHOW_ME).requestFocus()
        rule.onNodeWithTag(CoachMarkTags.SHOW_ME).assertIsFocused()
        rule.onNodeWithTag(CoachMarkTags.SHOW_ME).performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithTag(WalkthroughTags.POPOVER).assertIsDisplayed()
    }

    // ---- hints follow the input device (UX-124) ----------------------------------------------------------------------

    @Test
    fun hint_should_UseTheTouchWording_AfterATouch_AndTheKeyboardWordingAfterAKeyPress() {
        // Finger drawing ON: with it OFF a touch user gets the finger-off hint instead (UX-80, FingerDrawingController.TOOL_HINT_OFF).
        val state = show(desktop, SketchEditorState(fingerDrawing = true))
        state.chooseTool(ToolId.Rectangle)
        rule.waitForIdle()
        val hint = SketchStrings.Hints.of(ToolId.Rectangle)
        rule.onNodeWithTag(ShellTags.HINT).assertTextEquals(hint.keyboard)

        rule.onNodeWithTag(ShellTags.CANVAS).performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(InputKind.TouchOrPen, state.lastInput)
        rule.onNodeWithTag(ShellTags.HINT).assertTextEquals(hint.touch)

        rule.onNodeWithTag(ShellTags.CANVAS).requestFocus()
        rule.onNodeWithTag(ShellTags.CANVAS).performKeyInput { pressKey(Key.ShiftLeft) }
        rule.waitForIdle()
        assertEquals(InputKind.Keyboard, state.lastInput)
        rule.onNodeWithTag(ShellTags.HINT).assertTextEquals(hint.keyboard)
    }

    @Test
    fun hint_should_FollowTheActiveTool_AndPrefixToolLock() {
        val state = show(desktop, SketchEditorState(fingerDrawing = true))
        state.chooseTool(ToolId.Arrow)
        state.toolLock = true
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.HINT).assertTextEquals("${SketchStrings.Hints.TOOL_LOCK_ON} ${SketchStrings.Hints.of(ToolId.Arrow).keyboard}")
        assertNull(state.help.shown)
    }
}
