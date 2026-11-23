// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2.guide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The once-per-device dimensions tour: it starts the first time a shape with pills is selected, never twice, and Help search finds it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w480dp-h900dp-mdpi")
class DimensionTourTest {
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

    private val settings = MapSettings()
    private val tours = TourController(GuideContent.registry(), TourProgress(settings))

    @Test
    fun theTour_should_StartTheFirstTimeAShapeIsSelectedOnATechnicalSheet_AndNeverAgain() {
        var selected by mutableStateOf(false)
        rule.setContent { TourTriggers(tours, ToolId.Select, false, null, false, technicalShapeSelected = selected) }
        rule.waitForIdle()
        assertNull(tours.active)
        selected = true
        rule.waitForIdle()
        assertEquals(GuideContent.DIMENSIONS, tours.active?.id)
        tours.finish()
        selected = false
        rule.waitForIdle()
        selected = true
        rule.waitForIdle()
        assertNull(tours.active, "once per device")
        assertTrue(TourProgress(settings).isSeen(GuideContent.DIMENSIONS))
    }

    @Test
    fun theTour_should_BeSkippable_AndStayOffWhenTipsAreOff() {
        tours.setAutoTours(false)
        rule.setContent { TourTriggers(tours, ToolId.Select, false, null, false, technicalShapeSelected = true) }
        rule.waitForIdle()
        assertNull(tours.active)
        assertTrue(tours.replay(GuideContent.DIMENSIONS))
        tours.skipAll()
        assertNull(tours.active)
    }

    @Test
    fun helpSearch_should_FindTheDimensionsTopic_AndReplayItsTour() {
        rule.setContent { HelpSearchDialog(tours, ToolId.entries, emptyList(), {}, {}, null, autoFocus = false) }
        rule.onNodeWithTag(HelpSearchTags.FIELD).performTextInput("pill")
        rule.onNodeWithTag(HelpSearchTags.result("help.${GuideContent.DIMENSIONS}")).assertIsDisplayed()
    }
}
