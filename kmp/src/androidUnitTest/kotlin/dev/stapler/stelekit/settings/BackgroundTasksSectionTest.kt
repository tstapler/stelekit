// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

// jvmTest-reason: n/a (androidUnitTest, Robolectric); Gradle-only, not registered in AllAndroidUnitTests.
package dev.stapler.stelekit.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.git.GitSyncTask
import dev.stapler.stelekit.jobs.schedule.NetworkPolicy
import dev.stapler.stelekit.jobs.schedule.Schedule
import dev.stapler.stelekit.jobs.schedule.TaskConstraints
import dev.stapler.stelekit.jobs.schedule.TaskEntry
import dev.stapler.stelekit.jobs.schedule.TaskOverrides
import dev.stapler.stelekit.ui.components.settings.BackgroundTaskGraph
import dev.stapler.stelekit.ui.components.settings.BackgroundTasksSection
import dev.stapler.stelekit.ui.components.settings.SWITCH_LABEL
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 4.1.1 list and drill-in: UX-1, UX-5, UX-33, UX-34, the S1 empty state, platform hiding and the one-shot row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w400dp-h2400dp")
class BackgroundTasksSectionTest {
    @get:Rule
    val rule = createComposeRule()

    private fun graphs(n: Int) = (1..n).map { BackgroundTaskGraph("g$it", if (it == 1) "Work notes" else "Graph $it") }

    private fun show(fixture: BackgroundTaskFixture, graphs: List<BackgroundTaskGraph>, onAdd: () -> Unit = {}) {
        rule.setContent { MaterialTheme { BackgroundTasksSection(fixture.controller, graphs, onAdd) } }
        rule.waitForIdle()
    }

    @Test
    fun section_should_beHiddenOnNoopAndShownOnDesktop_When_SchedulesWorkVaries_Noop() {
        show(BackgroundTaskFixture(BackgroundTaskFixture.NOOP), graphs(2))
        rule.onNodeWithText("Background tasks").assertDoesNotExist()
        rule.onNodeWithText("Git sync", substring = true).assertDoesNotExist()
    }

    @Test
    fun section_should_beHiddenOnNoopAndShownOnDesktop_When_SchedulesWorkVaries_Desktop() {
        show(BackgroundTaskFixture(BackgroundTaskFixture.DESKTOP), graphs(2))
        rule.onNodeWithText("Background tasks").assertExists()
        rule.onNodeWithText("Git sync", substring = true).assertExists()
    }

    @Test
    fun list_should_showEmptyStateAddGraphAndSearchOnlyFromEightGraphs_When_GraphCountVaries() {
        var adds = 0
        show(BackgroundTaskFixture(BackgroundTaskFixture.ANDROID), emptyList()) { adds++ }
        rule.onNodeWithText("No graphs yet. Add a graph to schedule syncing.").assertExists()
        rule.onNodeWithText("Add graph").performClick()
        assertEquals(1, adds)
    }

    @Test
    fun search_should_beAbsentAtSevenAndPresentAtEightGraphs_When_KindOpened() {
        val seven = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        show(seven, graphs(7))
        rule.onNodeWithText("Git sync", substring = true).performClick()
        rule.onNodeWithText("Search graphs").assertDoesNotExist()
    }

    @Test
    fun search_should_haveLabelAndShowNoMatchesWithClear_When_QueryMatchesNothing() {
        show(BackgroundTaskFixture(BackgroundTaskFixture.ANDROID), graphs(8))
        rule.onNodeWithText("Git sync", substring = true).performClick()
        rule.onNodeWithText("Search graphs").assertExists()
        rule.onNodeWithContentDescription("Search graphs").assertExists()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("zzz")
        rule.onNodeWithText("No matches").assertExists()
        rule.onNodeWithText("Clear search").performClick()
        rule.onNodeWithText("No matches").assertDoesNotExist()
        rule.onNodeWithText("Work notes", substring = true).assertExists()
    }

    @Test
    fun list_should_showOneSummaryLinePerKindAndUsingDefaultOrCustomRows_When_25Graphs() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        val all = graphs(25)
        runBlocking {
            all.filterIndexed { i, _ -> i % 2 == 0 }.forEach {
                fixture.store.write(fixture.gitKey(it.id), TaskEntry(true, TaskOverrides(Schedule.of(360, 36), TaskConstraints())))
            }
        }
        show(fixture, all)
        // S1: one summary line per kind, no expanded S2 controls and no graph rows.
        rule.onNodeWithText("About every hour - Any network - on for 13 of 25 graphs").assertExists()
        rule.onNodeWithText(SWITCH_LABEL).assertDoesNotExist()
        rule.onNodeWithText("Work notes").assertDoesNotExist()
        rule.onNodeWithText("Git sync", substring = true).performClick()
        rule.onNodeWithText("Search graphs").assertExists()
        rule.onNodeWithText("Applies to 12 graphs", substring = true).assertExists()
        assertTrue(rule.onAllNodesWithText("On - Custom: every 6 h").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Off").fetchSemanticsNodes().isNotEmpty())
        // Lazy: far rows are not composed until scrolled to; no hard cap.
        rule.onNodeWithText("Graph 25").assertDoesNotExist()
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasText("Graph 25"))
        rule.onNodeWithText("Graph 25").assertExists()
    }

    @Test
    fun enableSync_should_takeAtMostThreeTaps_When_StartingFromSettings() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        show(fixture, graphs(2))
        rule.onNodeWithText("Git sync", substring = true).performClick() // 1
        rule.onNodeWithText("Work notes", substring = true).performClick() // 2
        rule.onNodeWithText(SWITCH_LABEL).performClick() // 3
        rule.waitForIdle()
        val (key, config) = fixture.scheduler.applied.single()
        assertEquals(fixture.gitKey("g1"), key)
        assertTrue(config.enabled)
        assertEquals(60, config.schedule.intervalMinutes)
        assertEquals(NetworkPolicy.Any, config.constraints.network)
    }

    @Test
    fun graphRow_should_exposeOneMergedNodeWithExactDescription_When_SemanticsRead() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        runBlocking {
            fixture.store.write(fixture.gitKey("g1"), TaskEntry(true, TaskOverrides(Schedule.of(60, 6), TaskConstraints(network = NetworkPolicy.Unmetered))))
        }
        show(fixture, graphs(2))
        rule.onNodeWithText("Git sync", substring = true).performClick()
        rule.onNode(
            SemanticsMatcher("merged graph row") {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() ==
                    "Git sync, Work notes, on, about every hour, unmetered networks only, Custom: unmetered networks only"
            },
        ).assertExists()
    }

    @Test
    fun defaultRow_should_showAppliesToNGraphsAndWriteDefaultsNotAScope_When_Edited() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        runBlocking { fixture.store.write(fixture.gitKey("g2"), TaskEntry(true, TaskOverrides(Schedule.of(360, 36), TaskConstraints()))) }
        show(fixture, graphs(3))
        rule.onNodeWithText("Git sync", substring = true).performClick()
        rule.onNodeWithText("Applies to 2 graphs", substring = true).assertExists()
        rule.onNodeWithText("Default for new graphs", substring = true).performClick()
        rule.onNodeWithText("2 hours").performClick()
        rule.waitForIdle()
        assertEquals(120, runBlocking { fixture.store.readDefaults(GitSyncTask.KIND) }?.schedule?.intervalMinutes)
        assertNull(runBlocking { fixture.store.readEntry(fixture.gitKey("g1")) }.overrides)
    }

    @Test
    fun modelDownloadRow_should_showOnlyNetworkChoice_When_OneShotKind() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        show(fixture, graphs(1))
        rule.onNodeWithText("Unmetered networks only - app-wide").assertExists()
        rule.onNodeWithText("Model download", substring = true).performClick()
        rule.onNodeWithText(SWITCH_LABEL).assertDoesNotExist()
        rule.onNodeWithText("Advanced").assertDoesNotExist()
        rule.onNodeWithText("1 hour").assertDoesNotExist()
        rule.onNodeWithText("Any network").performClick()
        rule.waitForIdle()
        val entry = runBlocking { fixture.store.readEntry(BackgroundTaskFixture.MODEL_DOWNLOAD_KEY) }
        assertEquals(false, entry.enabled)
        assertNull(entry.overrides?.schedule)
        assertEquals(NetworkPolicy.Any, entry.overrides?.constraints?.network)
        assertTrue(fixture.scheduler.applied.isEmpty())
    }

    @Test
    fun drillIn_should_moveFocusToTitleAndBackRestoreOpener_When_S1ToKindToGraph() {
        show(BackgroundTaskFixture(BackgroundTaskFixture.ANDROID), graphs(2))
        rule.onNodeWithText("Git sync", substring = true).performClick()
        rule.onNode(hasText("Git sync") and androidx.compose.ui.test.isFocusable()).assertIsFocused()
        rule.onNodeWithText("Work notes", substring = true).performClick()
        rule.onNodeWithText("Git sync - Work notes").assertIsFocused()
        rule.onNodeWithText("< Back").performClick()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Work notes", substring = true)).assertIsFocused()
        rule.onNodeWithText("< Back").performClick()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Git sync", substring = true)).assertIsFocused()
    }
}
