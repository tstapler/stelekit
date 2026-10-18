// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Robolectric Compose tests for [EmptyGraphStateScreen]. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class EmptyGraphStateScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyState_should_offerCreateAndDemo_when_createCallbackProvided() {
        var created = 0
        var demo = 0
        composeTestRule.setContent {
            MaterialTheme { EmptyGraphStateScreen(onTryDemo = { demo++ }, onCreateGraph = { created++ }) }
        }
        composeTestRule.onNodeWithText("No graphs yet").assertExists()
        composeTestRule.onNodeWithText("Create a Graph").performClick()
        composeTestRule.onNodeWithText("Try the Demo Graph Instead").performClick()
        assertEquals(1, created)
        assertEquals(1, demo)
    }

    @Test
    fun emptyState_should_offerOnlyDemo_when_noCreateCallback() {
        var demo = 0
        composeTestRule.setContent { MaterialTheme { EmptyGraphStateScreen(onTryDemo = { demo++ }) } }
        composeTestRule.onNodeWithText("This browser can't pick a folder. Try the demo graph instead.").assertExists()
        assertTrue(composeTestRule.onAllNodesWithText("Create a Graph").fetchSemanticsNodes().isEmpty())
        composeTestRule.onNodeWithText("Try Demo Graph").performClick()
        assertEquals(1, demo)
    }

    @Test
    fun emptyState_should_showErrorMessage_when_provided() {
        composeTestRule.setContent {
            MaterialTheme { EmptyGraphStateScreen(onTryDemo = {}, errorMessage = "Disk full") }
        }
        composeTestRule.onNodeWithText("Disk full").assertExists()
    }
}
