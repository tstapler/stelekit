// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.ui.sketch.v2.guide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.PanelTab
import dev.stapler.stelekit.ui.sketch.v2.PlaceRect
import dev.stapler.stelekit.ui.sketch.v2.TabStop
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tour popover, Help and search content, and the first-use triggers, composed without an AWT window. The same dialog is
 * also covered by a Robolectric test; this one runs under jvmTest, which is what the diff-coverage gate counts.
 */
@OptIn(ExperimentalTestApi::class)
class GuideUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val progress = TourProgress(MapSettings())
    private val tours = TourController(GuideContent.registry(), progress)

    private fun gone(tag: String) = rule.onAllNodesWithTag(tag).assertCountZero()

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountZero() =
        assertEquals(0, fetchSemanticsNodes().size)

    // ---- the tour popover ------------------------------------------------------------------------------------------

    @Test
    fun overlay_should_ShowTheStep_StepForward_AndEndWithDone() {
        tours.replay(GuideContent.POINT_DRAG)
        rule.setContent { TourOverlay(tours) }
        rule.onNodeWithTag(TourTags.TITLE).assertIsDisplayed()
        rule.onNodeWithTag(TourTags.INDICATOR).assertIsDisplayed()
        rule.onNodeWithTag(TourTags.NEXT).performClick()
        rule.waitForIdle()
        assertEquals(1, tours.stepIndex)
        rule.onNodeWithTag(TourTags.DONE).performClick()
        rule.waitForIdle()
        assertNull(tours.active)
        gone(TourTags.POPOVER)
    }

    @Test
    fun overlay_should_GoBack_Skip_AndSkipAll() {
        tours.replay(GuideContent.POINT_DRAG)
        rule.setContent { TourOverlay(tours) }
        rule.onNodeWithTag(TourTags.NEXT).performClick()
        rule.onNodeWithTag(TourTags.BACK).performClick()
        rule.waitForIdle()
        assertEquals(0, tours.stepIndex)
        rule.onNodeWithTag(TourTags.SKIP).performClick()
        rule.waitForIdle()
        assertNull(tours.active)
        tours.replay(GuideContent.POINT_DRAG)
        rule.waitForIdle()
        rule.onNodeWithTag(TourTags.SKIP_ALL).performClick()
        rule.waitForIdle()
        assertNull(tours.active)
        assertFalse(tours.autoTours)
    }

    @Test
    fun overlay_should_DrawARing_WhenTheStepsAnchorIsOnScreen() {
        tours.anchors[TabStop.Canvas] = PlaceRect(10f, 10f, 110f, 110f)
        tours.replay(GuideContent.POINT_DRAG)
        rule.setContent { TourOverlay(tours) }
        rule.onNodeWithTag(dev.stapler.stelekit.ui.sketch.v2.WalkthroughTags.RING).assertIsDisplayed()
    }

    @Test
    fun overlay_should_ShowNothing_WhenNoTourIsActive() {
        rule.setContent { TourOverlay(tours) }
        gone(TourTags.POPOVER)
    }

    @Test
    fun overlay_should_EndOnEscape() {
        tours.replay(GuideContent.POINT_DRAG)
        rule.setContent { TourOverlay(tours) }
        rule.waitForIdle()
        rule.onNodeWithTag(TourTags.POPOVER).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertNull(tours.active)
    }

    // ---- first-use triggers ----------------------------------------------------------------------------------------

    @Test
    fun triggers_should_StartTheMatchingTour_WhenTheUserFirstReachesAFeature() {
        var tool by mutableStateOf(ToolId.Select)
        var menu by mutableStateOf(false)
        var panel by mutableStateOf<PanelTab?>(null)
        var line by mutableStateOf(false)
        rule.setContent { TourTriggers(tours, tool, menu, panel, line) }
        rule.waitForIdle()
        assertNull(tours.active)
        tool = ToolId.Point
        rule.waitForIdle()
        assertEquals(GuideContent.CONSTRAINTS, tours.active?.id)
        tours.finish()
        panel = PanelTab.Technical
        rule.waitForIdle()
        assertEquals(GuideContent.TECHNICAL, tours.active?.id)
        tours.finish()
        line = true
        rule.waitForIdle()
        assertEquals(GuideContent.POINT_DRAG, tours.active?.id)
        tours.finish()
        menu = true
        rule.waitForIdle()
        assertNull(tours.active)
    }

    @Test
    fun triggers_should_StartTheConstraintsTour_FromTheConstrainMenu() {
        var menu by mutableStateOf(false)
        rule.setContent { TourTriggers(tours, ToolId.Select, menu, null, false) }
        menu = true
        rule.waitForIdle()
        assertEquals(GuideContent.CONSTRAINTS, tours.active?.id)
    }

    @Test
    fun triggers_should_DoNothing_WithoutTours() {
        rule.setContent { TourTriggers(null, ToolId.Point, true, PanelTab.Constraints, true) }
        rule.waitForIdle()
        assertNull(tours.active)
    }

    // ---- Help and search -------------------------------------------------------------------------------------------

    private var ran: SearchAction? = null
    private var dismissed = 0

    private fun show(startTopic: String? = null, withTours: Boolean = true) = rule.setContent {
        HelpSearchContent(
            if (withTours) tours else null, ToolId.entries, listOf(GuideActions.UNDO, GuideActions.CONSTRAIN),
            { ran = it }, { dismissed += 1 }, startTopic, autoFocus = false,
        )
    }

    @Test
    fun help_should_ListTopicsAndTheTipsSwitch_AndHideTheSwitchWithoutTours() {
        show()
        rule.onNodeWithTag(HelpSearchTags.topic(GuideContent.CONSTRAINTS)).assertIsDisplayed()
        rule.onNodeWithTag(HelpSearchTags.TIPS).assertIsDisplayed()
    }

    @Test
    fun help_should_OmitTheTipsSwitch_WithoutTours() {
        show(withTours = false)
        gone(HelpSearchTags.TIPS)
    }

    @Test
    fun search_should_FindAnActionAndRunIt() {
        show()
        rule.onNodeWithTag(HelpSearchTags.FIELD).performTextInput("undo")
        rule.onNodeWithTag(HelpSearchTags.result("action.undo")).performClick()
        assertEquals(SearchAction.Run(GuideActions.UNDO), ran)
    }

    @Test
    fun search_should_FindAToolByName() {
        show()
        rule.onNodeWithTag(HelpSearchTags.FIELD).performTextInput("ellip")
        rule.onNodeWithTag(HelpSearchTags.result("tool.ellipse")).performClick()
        assertEquals(SearchAction.UseTool(ToolId.Ellipse), ran)
    }

    @Test
    fun search_should_SayNothingMatches_ForAQueryWithNoResult() {
        show()
        rule.onNodeWithTag(HelpSearchTags.FIELD).performTextInput("zzzzqq")
        rule.onNodeWithTag(HelpSearchTags.EMPTY).assertIsDisplayed()
    }

    @Test
    fun aTopic_should_ShowItsText_AndReplayItsTour_AndClose() {
        show()
        rule.onNodeWithTag(HelpSearchTags.topic(GuideContent.CONSTRAINTS)).performClick()
        rule.onNodeWithTag(HelpSearchTags.BODY).assertIsDisplayed()
        rule.onNodeWithTag(HelpSearchTags.REPLAY).performClick()
        assertEquals(GuideContent.CONSTRAINTS, tours.active?.id)
        assertEquals(1, dismissed)
    }

    @Test
    fun aSearchResultTopic_should_OpenInsideTheDialog_AndBackReturnsToTheResults() {
        show()
        rule.onNodeWithTag(HelpSearchTags.FIELD).performTextInput("keyboard")
        rule.onNodeWithTag(HelpSearchTags.result("help.keyboard")).performClick()
        rule.onNodeWithTag(HelpSearchTags.BODY).assertIsDisplayed()
        gone(HelpSearchTags.REPLAY)
        rule.onNodeWithTag(HelpSearchTags.BACK).performClick()
        rule.onNodeWithTag(HelpSearchTags.result("help.keyboard")).assertIsDisplayed()
        assertNull(ran)
    }

    @Test
    fun close_should_Dismiss_AndTheTipsSwitchShouldToggleAutomaticTours() {
        show()
        rule.onNodeWithTag(HelpSearchTags.TIPS).performClick()
        assertFalse(tours.autoTours)
        rule.onNodeWithTag(HelpSearchTags.TIPS).performClick()
        assertTrue(tours.autoTours)
        rule.onNodeWithTag(HelpSearchTags.CLOSE).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun startTopic_should_OpenThatTopic() {
        show(startTopic = "keyboard")
        rule.onNodeWithTag(HelpSearchTags.BODY).assertIsDisplayed()
    }
}
