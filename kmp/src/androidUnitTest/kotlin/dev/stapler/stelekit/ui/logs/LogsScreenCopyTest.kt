// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.logs

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogLevel
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.platform.diagnostics.ExitRecord
import dev.stapler.stelekit.platform.diagnostics.formatExitRecord
import dev.stapler.stelekit.ui.components.LogDashboard
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LogsScreenCopyTest {
    @get:Rule
    val rule = createComposeRule()

    private val exitLine = formatExitRecord(
        ExitRecord(9, 400, 0, 210_000, 300_000, 1_700_000_000_000, "excessive cpu 61000 during 300000 dur=300000 limit=25"),
    )

    @Before
    fun seedLogs() {
        LogManager.clearLogs()
        repeat(5) { n ->
            LogManager.addLog(
                LogEntry(
                    LogLevel.INFO, "DiagnosticsSample",
                    "DiagnosticsSample cpuMsDelta=$n threads=main:1 foreground=false heapMb=1/2 coilCacheKb=3 " +
                        "undoDepth=1 screen=AnnotationEditor tool=DISTANCE image=10x10",
                ),
            )
        }
        LogManager.addLog(LogEntry(LogLevel.INFO, "Other", "opened /home/alice/Notes/Kitchen-Reno/pages/Secret.md"))
        // Newest first in the list, so the exit record sits at the top of the viewport.
        LogManager.addLog(LogEntry(LogLevel.WARN, "PrevSession", exitLine))
    }

    @After
    fun clearLogs() = LogManager.clearLogs()

    private fun clipboardText(): String? {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }

    @Test
    fun logs_should_ShowExitFieldsAndCopyWithConfirmation_When_ReplayRendered() {
        rule.setContent { LogDashboard() }

        rule.onNodeWithText("EXCESSIVE_RESOURCE_USAGE", substring = true).assertExists()
        rule.onNodeWithContentDescription("Copy exit info").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Copied. Review before posting publicly.").assertExists()
        val copied = assertNotNull(clipboardText())
        assertTrue(copied.contains("importance=CACHED") && copied.contains("pss=205MB"), copied)
        assertTrue(copied.contains("excessive cpu 61000 during 300000 dur=300000 limit=25"), copied)
    }

    @Test
    fun redactForShare_should_RemovePathsGraphAndPageNamesAndCopyOnlyVisibleExitBlock_When_PlantedValuesPresent() {
        rule.setContent {
            LogDashboard(redactionGraphNames = listOf("Kitchen-Reno"), redactionPageNames = { listOf("Secret") })
        }

        rule.onNodeWithContentDescription("Copy exit info").performClick()
        rule.waitForIdle()

        val copied = assertNotNull(clipboardText())
        assertFalse(copied.contains("alice"), copied)
        assertFalse(copied.contains("Kitchen-Reno"), copied)
        assertFalse(copied.contains("Secret"), copied)
        assertFalse(copied.contains("opened"), "an unrelated log line must not be copied: $copied")
        assertTrue(copied.lines().count { it.contains("cpuMsDelta=") } <= 3, copied)
    }
}
