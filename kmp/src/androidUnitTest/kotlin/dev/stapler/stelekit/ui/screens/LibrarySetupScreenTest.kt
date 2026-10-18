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

/** Robolectric Compose tests for [LibrarySetupScreen]. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class LibrarySetupScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun setup_should_fireChooseFolder_when_buttonClicked() {
        var chosen = 0
        composeTestRule.setContent { MaterialTheme { LibrarySetupScreen(onChooseFolder = { chosen++ }) } }
        composeTestRule.onNodeWithText("Choose your notes folder").assertExists()
        assertTrue(composeTestRule.onAllNodesWithText("Permission denied").fetchSemanticsNodes().isEmpty())
        composeTestRule.onNodeWithText("Choose Folder").performClick()
        assertEquals(1, chosen)
    }

    @Test
    fun setup_should_showError_when_errorMessageProvided() {
        composeTestRule.setContent {
            MaterialTheme { LibrarySetupScreen(onChooseFolder = {}, errorMessage = "Permission denied") }
        }
        composeTestRule.onNodeWithText("Permission denied").assertExists()
    }
}
