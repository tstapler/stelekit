// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

// jvmTest-reason: n/a (androidUnitTest, Robolectric); Gradle-only, not registered in AllAndroidUnitTests.
package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.git.GitSyncTask
import dev.stapler.stelekit.jobs.schedule.ConstraintState
import dev.stapler.stelekit.jobs.schedule.EnglishRunTimeFormatter
import dev.stapler.stelekit.jobs.schedule.LocaleRunTimeFormatter
import dev.stapler.stelekit.jobs.schedule.RunSource
import dev.stapler.stelekit.jobs.schedule.SettingsTaskRunLog
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskRegistry
import dev.stapler.stelekit.jobs.schedule.TaskRunLog
import dev.stapler.stelekit.jobs.schedule.TaskRunRecord
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.jobs.schedule.formatRunTime
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.settings.FailableSettings
import dev.stapler.stelekit.ui.components.LogDashboard
import dev.stapler.stelekit.ui.components.MAX_RUN_ROWS
import dev.stapler.stelekit.ui.components.RUN_HISTORY_RETRY
import dev.stapler.stelekit.ui.components.RUN_HISTORY_TITLE
import dev.stapler.stelekit.ui.components.RUN_HISTORY_UNREADABLE
import dev.stapler.stelekit.ui.components.TaskRunLogSection
import dev.stapler.stelekit.ui.components.TaskRunLogSource
import dev.stapler.stelekit.ui.components.rememberTaskRunRowsState
import dev.stapler.stelekit.ui.components.settings.BackgroundTaskGraph
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Story 4.1.3 / UX-31: Logs rows for background runs, with display names and one time string for eye and screen reader. */
@RunWith(RobolectricTestRunner::class)
class TaskRunLogRowsTest {
    @get:Rule
    val rule = createComposeRule()

    @After
    fun clearLogs() = LogManager.clearLogs()

    // 2026-11-10 12:00:00 UTC
    private val now = 1_794_312_000_000L
    private val minute = 60_000L
    private val gitKey = TaskKey(GitSyncTask.KIND, TaskScope("g1"))
    private val graphs = listOf(BackgroundTaskGraph("g1", "Work notes"))
    private val settings = FailableSettings()
    private val runLog: TaskRunLog = SettingsTaskRunLog(settings)

    private val registry = TaskRegistry().also { it.register(GitSyncTask.KIND, GitSyncTask.spec, GitSyncTask()) }

    private fun source(log: TaskRunLog = runLog, registry: TaskRegistry = this.registry) =
        TaskRunLogSource(log, registry, graphs, EnglishRunTimeFormatter, { now }, TimeZone.UTC)

    private fun record(
        startedAtMs: Long,
        result: TaskRunResult = TaskRunResult.Success,
        source: RunSource = RunSource.Background,
        key: TaskKey = gitKey,
    ) = TaskRunRecord(key, source, null, startedAtMs, result, ConstraintState(true, false, null))

    private fun append(vararg records: TaskRunRecord) = runBlocking { records.forEach { runLog.append(it) } }

    private fun show(source: TaskRunLogSource, query: String = "") {
        rule.setContent {
            MaterialTheme {
                val (state, retry) = rememberTaskRunRowsState(source)
                TaskRunLogSection(state, query, retry)
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun row_should_readTaskGraphAndResultWithStartTime_When_RecordIsSkippedMetered() {
        append(record(now - 12 * minute, TaskRunResult.Skipped("metered")))
        show(source())
        rule.onNodeWithText("Git sync / Work notes / skipped: metered").assertExists()
        rule.onNodeWithText("12 min ago").assertExists()
    }

    @Test
    fun rows_should_showBothSourcesNewestFirst_When_ForegroundAndBackgroundRecordsExist() {
        append(
            record(now - 30 * minute, source = RunSource.Background),
            record(now - 5 * minute, source = RunSource.Foreground),
        )
        show(source())
        val rows = rule.onAllNodesWithText("Git sync / Work notes / success")
        rows.assertCountEquals(2)
        val newest = rule.onNodeWithText("5 min ago").getUnclippedBoundsInRoot().top
        val older = rule.onNodeWithText("30 min ago").getUnclippedBoundsInRoot().top
        assertTrue(newest < older, "newest row must be above the older one")
    }

    @Test
    fun rows_should_neverShowRawIds_When_GraphHasDisplayName() {
        append(record(now - minute))
        show(source())
        rule.onAllNodesWithText("g1", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("git-sync", substring = true).assertCountEquals(0)
    }

    @Test
    fun rows_should_omitGraphSegment_When_TaskIsAppWide() {
        val appWide = TaskRegistry().also {
            it.register(
                dev.stapler.stelekit.jobs.schedule.TaskKind("model-download"),
                GitSyncTask.spec.copy(title = "Depth model download", shape = dev.stapler.stelekit.jobs.schedule.TaskShape.OneShot),
                GitSyncTask(),
            )
        }
        val key = TaskKey(dev.stapler.stelekit.jobs.schedule.TaskKind("model-download"), TaskScope(""))
        append(record(now - minute, TaskRunResult.Failed("offline"), key = key))
        show(source(registry = appWide))
        rule.onNodeWithText("Depth model download / failed: offline").assertExists()
    }

    @Test
    fun time_should_beRelativeTodayOrDated_When_RecordAgeVaries() {
        val zone = TimeZone.UTC
        assertEquals("12 min ago", formatRunTime(now - 12 * minute, now, zone, EnglishRunTimeFormatter))
        assertEquals("just now", formatRunTime(now - 20_000L, now, zone, EnglishRunTimeFormatter))
        assertEquals("08:00", formatRunTime(now - 4 * 60 * minute, now, zone, EnglishRunTimeFormatter))
        assertEquals("Nov 9, 21:15", formatRunTime(now - (14 * 60 + 45) * minute, now, zone, EnglishRunTimeFormatter))
    }

    @Test
    fun time_should_followLocale_When_FormattingClockAndDate() {
        val zone = TimeZone.UTC
        val us = LocaleRunTimeFormatter(Locale.US)
        val de = LocaleRunTimeFormatter(Locale.GERMANY)
        val at = now - (14 * 60 + 45) * minute
        assertEquals("9:15 PM", us.timeOfDay(at, zone).replace(' ', ' '))
        assertEquals("21:15", de.timeOfDay(at, zone))
        assertNotEquals(us.dateAndTime(at, zone), de.dateAndTime(at, zone))
        assertTrue(us.dateAndTime(at, zone).startsWith("Nov 9, "))
    }

    @Test
    fun screenReaderText_should_carryTheSameTimeAndLabels_When_RowIsMerged() {
        append(record(now - 12 * minute, TaskRunResult.Skipped("metered")), record(now - (14 * 60 + 45) * minute))
        show(source())
        rule.onNodeWithContentDescription("Git sync, Work notes, skipped: metered, 12 min ago").assertExists()
        rule.onNodeWithContentDescription("Git sync, Work notes, success, Nov 9, 21:15").assertExists()
        rule.onNodeWithText("Nov 9, 21:15").assertExists()
    }

    @Test
    fun section_should_showNothing_When_RunLogIsEmpty() {
        show(source())
        rule.onAllNodesWithText(RUN_HISTORY_TITLE).assertCountEquals(0)
        rule.onAllNodesWithText(RUN_HISTORY_UNREADABLE).assertCountEquals(0)
    }

    @Test
    fun section_should_offerRetryThatRereads_When_RunLogThrows() {
        var failing by mutableStateOf(true)
        val flaky = object : TaskRunLog {
            override suspend fun append(record: TaskRunRecord) = Unit
            override suspend fun recent(key: TaskKey): List<TaskRunRecord> =
                if (failing) error("read failed") else listOf(record(now - minute))
            override suspend fun lastSuccessAtMs(key: TaskKey): Long? = null
        }
        show(source(flaky))
        rule.onNodeWithText(RUN_HISTORY_UNREADABLE).assertExists()
        failing = false
        rule.onNodeWithText(RUN_HISTORY_RETRY).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText(RUN_HISTORY_UNREADABLE).assertCountEquals(0)
        rule.onNodeWithText("Git sync / Work notes / success").assertExists()
    }

    @Test
    fun rows_should_beCappedAndSaySo_When_MoreRunsThanTheCap() {
        val many = MAX_RUN_ROWS + 10
        val stored = (1..many).map { record(now - it * minute, TaskRunResult.Skipped("run$it")) }
        val big = object : TaskRunLog {
            override suspend fun append(record: TaskRunRecord) = Unit
            override suspend fun recent(key: TaskKey) = stored
            override suspend fun lastSuccessAtMs(key: TaskKey): Long? = null
        }
        show(source(big))
        rule.onAllNodes(hasText("Git sync / Work notes / skipped", substring = true)).assertCountEquals(MAX_RUN_ROWS)
        rule.onNodeWithText("Showing newest $MAX_RUN_ROWS of $many runs").assertExists()
        rule.onNodeWithText("Git sync / Work notes / skipped: run1").assertExists()
    }

    @Test
    fun rows_should_filterByQuery_When_SearchTextGiven() {
        append(record(now - 2 * minute, TaskRunResult.Skipped("metered")), record(now - minute, TaskRunResult.Failed("offline")))
        show(source(), query = "METERED")
        rule.onNodeWithText("Git sync / Work notes / skipped: metered").assertExists()
        rule.onAllNodesWithText("failed: offline", substring = true).assertCountEquals(0)
    }

    @Test
    fun logsScreen_should_listRunsAndNotCrash_When_DashboardHasTaskRuns() {
        LogManager.clearLogs()
        append(record(now - 12 * minute, TaskRunResult.Skipped("metered")))
        rule.setContent { MaterialTheme { LogDashboard(taskRuns = source()) } }
        rule.waitForIdle()
        rule.onNodeWithText("Git sync / Work notes / skipped: metered").assertExists()
    }
}
