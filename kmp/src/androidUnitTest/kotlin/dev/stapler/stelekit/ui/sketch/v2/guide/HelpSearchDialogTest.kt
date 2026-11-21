// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2.guide

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Help and search: typing finds tools and actions, a topic opens with its text, Replay starts its tour. Robolectric; Gradle-only (see BUILD.bazel). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w480dp-h900dp-mdpi")
class HelpSearchDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private class MapSettings : Settings {
        val map = HashMap<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = key in map
    }

    private val tours = TourController(GuideContent.registry(), TourProgress(MapSettings()))
    private var ran: SearchAction? = null
    private var dismissed = 0

    private fun show(startTopic: String? = null) = rule.setContent {
        HelpSearchDialog(tours, ToolId.entries, listOf(GuideActions.UNDO, GuideActions.CONSTRAIN), { ran = it }, { dismissed += 1 }, startTopic, autoFocus = false)
    }

    @Test
    fun emptyQuery_should_ListTheTopics_AndTheTipsSwitch() {
        show()
        rule.onNodeWithTag(HelpSearchTags.topic(GuideContent.CONSTRAINTS)).assertIsDisplayed()
        rule.onNodeWithTag(HelpSearchTags.TIPS).assertIsDisplayed()
    }

    @Test
    fun typing_should_FindAnActionAndRunIt() {
        show()
        rule.onNodeWithTag(HelpSearchTags.FIELD).performTextInput("undo")
        rule.onNodeWithTag(HelpSearchTags.result("action.undo")).performClick()
        assertEquals(SearchAction.Run(GuideActions.UNDO), ran)
    }

    @Test
    fun typing_should_FindAToolAndUseIt() {
        show()
        rule.onNodeWithTag(HelpSearchTags.FIELD).performTextInput("ellip")
        rule.onNodeWithTag(HelpSearchTags.result("tool.ellipse")).performClick()
        assertEquals(SearchAction.UseTool(ToolId.Ellipse), ran)
    }

    @Test
    fun noMatch_should_SayNothingMatches() {
        show()
        rule.onNodeWithTag(HelpSearchTags.FIELD).performTextInput("zzzzqq")
        rule.onNodeWithTag(HelpSearchTags.EMPTY).assertIsDisplayed()
    }

    @Test
    fun aTopic_should_ShowItsText_AndReplayTheTourAndClose() {
        show()
        rule.onNodeWithTag(HelpSearchTags.topic(GuideContent.CONSTRAINTS)).performClick()
        rule.onNodeWithTag(HelpSearchTags.BODY).assertIsDisplayed()
        rule.onNodeWithTag(HelpSearchTags.REPLAY).performClick()
        assertEquals(GuideContent.CONSTRAINTS, tours.active?.id)
        assertEquals(1, dismissed)
    }

    @Test
    fun aTopicWithoutATour_should_OfferNoReplay() {
        show(startTopic = "keyboard")
        rule.onNodeWithTag(HelpSearchTags.BODY).assertIsDisplayed()
        rule.onNodeWithTag(HelpSearchTags.BACK).assertIsDisplayed()
        rule.onAllNodesWithTag(HelpSearchTags.REPLAY).assertCountEquals(0)
    }

    @Test
    fun tipsSwitch_should_TurnAutomaticToursOffAndOn() {
        show()
        rule.onNodeWithTag(HelpSearchTags.TIPS).performClick()
        assertEquals(false, tours.autoTours)
        rule.onNodeWithTag(HelpSearchTags.TIPS).performClick()
        assertEquals(true, tours.autoTours)
    }
}
