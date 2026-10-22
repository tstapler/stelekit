// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertCountEquals
import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogLevel
import dev.stapler.stelekit.logging.LogManager
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LogDashboardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var savedMinLevel = LogManager.minLevel

    // LogManager is a process-wide singleton: a raised minLevel would drop the entries these tests add, and
    // leftover entries would leak into other test classes.
    @Before
    fun lowerMinLevel() {
        savedMinLevel = LogManager.minLevel
        LogManager.minLevel = LogLevel.DEBUG
        LogManager.clearLogs()
    }

    @After
    fun restoreMinLevel() {
        LogManager.minLevel = savedMinLevel
        LogManager.clearLogs()
    }

    private fun show() {
        composeTestRule.setContent { MaterialTheme { LogDashboard() } }
    }

    @Test
    fun dashboard_should_RenderTitleAndSearchField() {
        show()

        composeTestRule.onNodeWithText("App Logs").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search logs...").assertIsDisplayed()
    }

    @Test
    fun dashboard_should_ListLoggedEntries() {
        LogManager.addLog(LogEntry(LogLevel.INFO, "LogDashboardTest", "smoke-entry-visible"))
        show()

        composeTestRule.onNodeWithText("smoke-entry-visible", substring = true).assertIsDisplayed()
    }

    @Test
    fun search_should_FilterOutNonMatchingEntries() {
        LogManager.addLog(LogEntry(LogLevel.INFO, "LogDashboardTest", "needle-entry-alpha"))
        LogManager.addLog(LogEntry(LogLevel.INFO, "LogDashboardTest", "haystack-entry-beta"))
        show()

        composeTestRule.onNodeWithText("Search logs...").performTextInput("needle-entry")

        composeTestRule.onNodeWithText("needle-entry-alpha", substring = true).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("haystack-entry-beta", substring = true).assertCountEquals(0)
    }
}
