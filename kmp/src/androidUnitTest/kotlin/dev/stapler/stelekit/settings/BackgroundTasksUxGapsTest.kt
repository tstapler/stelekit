// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

// jvmTest-reason: n/a (androidUnitTest, Robolectric); Gradle-only, not registered in AllAndroidUnitTests.
package dev.stapler.stelekit.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import dev.stapler.stelekit.git.COULDNT_READ_SETTINGS
import dev.stapler.stelekit.git.ScheduledSyncStatus
import dev.stapler.stelekit.ui.components.settings.BACK_ARROW_TAG
import dev.stapler.stelekit.ui.components.settings.BackgroundTaskGraph
import dev.stapler.stelekit.ui.components.settings.BackgroundTasksSection
import dev.stapler.stelekit.ui.components.settings.FETCH_ONLY_NOTE
import dev.stapler.stelekit.ui.components.settings.NAV_CHEVRON_TAG
import dev.stapler.stelekit.ui.components.settings.OPEN_GRAPH_FOR_STATUS
import dev.stapler.stelekit.ui.components.settings.ScheduledSyncSection
import dev.stapler.stelekit.ui.components.settings.ScheduledSyncSettingsState
import dev.stapler.stelekit.ui.components.settings.SwitchGate
import dev.stapler.stelekit.jobs.schedule.TaskKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** UX gap repairs: G6 (SAF fetch-only line), G4/UX-14 (non-active graphs), UX-15 (S1 read failure), UX-36 (mirrored nav), legacy radio arrow keys. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w400dp-h2400dp")
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class BackgroundTasksUxGapsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(
        fixture: BackgroundTaskFixture,
        graphs: List<BackgroundTaskGraph>,
        gate: (TaskKind, String) -> SwitchGate? = { _, _ -> null },
    ) {
        rule.setContent { MaterialTheme { BackgroundTasksSection(fixture.controller, graphs, {}, switchGate = gate) } }
        rule.waitForIdle()
    }

    private fun openGraph(name: String) {
        rule.onNodeWithText("Git sync", substring = true).performClick()
        rule.onNodeWithText(name, substring = true).performClick()
        rule.waitForIdle()
    }

    @Test
    fun graphPanel_should_showFetchOnlyLine_When_GraphIsSafBacked() {
        val graphs = listOf(BackgroundTaskGraph("g1", "Work notes", fetchOnly = true), BackgroundTaskGraph("g2", "Plain"))
        show(BackgroundTaskFixture(BackgroundTaskFixture.ANDROID), graphs)
        openGraph("Work notes")
        rule.onNodeWithText(FETCH_ONLY_NOTE).assertExists()
        assertEquals("This graph can only check for updates in the background", FETCH_ONLY_NOTE)
    }

    @Test
    fun graphPanel_should_omitFetchOnlyLine_When_GraphIsNotSafBacked() {
        show(BackgroundTaskFixture(BackgroundTaskFixture.ANDROID), listOf(BackgroundTaskGraph("g1", "Work notes")))
        openGraph("Work notes")
        rule.onNodeWithText(FETCH_ONLY_NOTE).assertDoesNotExist()
    }

    @Test
    fun graphPanel_should_sayOpenGraphToSeeStatus_When_GraphIsNotActive() {
        show(BackgroundTaskFixture(BackgroundTaskFixture.ANDROID), listOf(BackgroundTaskGraph("g1", "Work notes", active = false)))
        openGraph("Work notes")
        rule.onNodeWithText(OPEN_GRAPH_FOR_STATUS).assertExists()
    }

    @Test
    fun graphPanel_should_showGateReasonNotOpenGraphCopy_When_ActiveGraphHasGate() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        show(fixture, listOf(BackgroundTaskGraph("g1", "Work notes"))) { _, _ -> SwitchGate.NoRemote {} }
        openGraph("Work notes")
        rule.onNodeWithText(OPEN_GRAPH_FOR_STATUS).assertDoesNotExist()
        rule.onNodeWithText("Set up Git sync first").assertExists()
    }

    @Test
    fun kindSummary_should_showCouldntReadSettingsAndRetry_When_ReadFails() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        fixture.settings.failReads = true
        show(fixture, listOf(BackgroundTaskGraph("g1", "Work notes")))
        // Git sync (graph read) and Model download (app-wide read) both fail closed.
        rule.onAllNodesWithText(COULDNT_READ_SETTINGS.substringBefore(". "), substring = true).assertCountEquals(2)
        rule.onNodeWithText("About every hour", substring = true).assertDoesNotExist()
        fixture.settings.failReads = false
        rule.onNodeWithText("Retry").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("About every hour", substring = true).assertExists()
        rule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun navigation_should_useAutoMirroredIconsNotLiteralArrows_When_ListAndDrillInShown() {
        show(BackgroundTaskFixture(BackgroundTaskFixture.ANDROID), listOf(BackgroundTaskGraph("g1", "Work notes")))
        rule.onNodeWithText(">").assertDoesNotExist()
        rule.onAllNodesWithTag(NAV_CHEVRON_TAG, useUnmergedTree = true).assertCountEquals(2)
        rule.onNodeWithText("Git sync", substring = true).performClick()
        rule.onNodeWithText("< Back").assertDoesNotExist()
        rule.onNodeWithText("Back").assertExists()
        rule.onAllNodesWithTag(BACK_ARROW_TAG, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun legacyIntervalPicker_should_moveSelectionWithArrowKeys_When_RadioFocused() {
        var picked: Int? = null
        val state = ScheduledSyncSettingsState(
            hasRemote = true, enabled = false, configuredIntervalMinutes = 15, minimumIntervalMinutes = 1,
            closedAppChecks = false, backgroundRestricted = false, status = ScheduledSyncStatus(),
            onEnabledChange = {}, onIntervalChange = { picked = it }, onResume = {}, onSetUpGit = {}, onFix = {},
        )
        rule.setContent { MaterialTheme { ScheduledSyncSection(state, nowMs = 0L) } }
        rule.onNodeWithText("15 minutes").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithText("15 minutes").performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        assertEquals(30, picked)
    }
}
