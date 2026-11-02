// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.logs

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogLevel
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.components.LogDashboard
import dev.stapler.stelekit.ui.components.PIPELINE_LOG_FILTER
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 5.5e: `Screen.Logs` carries an optional initial filter that the Logs screen applies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LogsScreenFilterTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun seedLogs() {
        LogManager.addLog(LogEntry(LogLevel.INFO, "AssetPipelineService", "pipeline-line-7731"))
        LogManager.addLog(LogEntry(LogLevel.INFO, "GraphLoader", "unrelated-line-7731"))
    }

    @Test
    fun viewLog_should_OpenLogsPrefilteredToPipelineLines_When_DeveloperModeOff() {
        seedLogs()
        composeRule.setContent {
            val screen = Screen.Logs(PIPELINE_LOG_FILTER)
            LogDashboard(initialFilter = screen.initialFilter)
        }

        // LogManager is process-global, so an earlier test may have added its own copies of the lines.
        assertTrue(composeRule.onAllNodes(hasText("pipeline-line-7731", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, composeRule.onAllNodes(hasText("unrelated-line-7731", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun logsWithoutFilter_should_ShowEveryLine_And_ExistingCallSitesKeepTheirMeaning() {
        seedLogs()
        composeRule.setContent { LogDashboard(initialFilter = Screen.Logs().initialFilter) }

        assertTrue(composeRule.onAllNodes(hasText("pipeline-line-7731", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodes(hasText("unrelated-line-7731", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertEquals(Screen.Logs(), Screen.Logs(null))
    }
}
