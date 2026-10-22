// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

// Smoke tests: every optional collaborator is null/empty, so each tab must render its empty state without crashing.
class PerformanceDashboardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val tabs = listOf("Histograms", "Traces", "Events", "Plans", "SQL Stats")

    private fun show() {
        composeTestRule.setContent { MaterialTheme { PerformanceDashboard() } }
    }

    @Test
    fun dashboard_should_RenderAllTabHeaders() {
        show()

        tabs.forEach { composeTestRule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun dashboard_should_SwitchThroughEveryTab_WithoutCrashing() {
        show()

        tabs.forEach { tab ->
            composeTestRule.onNodeWithText(tab).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText(tab).assertIsDisplayed()
        }
    }
}
