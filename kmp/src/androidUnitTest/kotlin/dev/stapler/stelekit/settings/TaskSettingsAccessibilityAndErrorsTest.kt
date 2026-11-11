// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.stapler.stelekit.git.COULDNT_READ_SETTINGS
import dev.stapler.stelekit.git.COULDNT_SAVE
import dev.stapler.stelekit.git.LOADING_SETTINGS_REASON
import dev.stapler.stelekit.git.ScheduledSyncStatus
import dev.stapler.stelekit.git.SETTINGS_UNREADABLE_TEXT
import dev.stapler.stelekit.jobs.schedule.LateCause
import dev.stapler.stelekit.jobs.schedule.RestrictionReason
import dev.stapler.stelekit.jobs.schedule.ScheduleLateness
import dev.stapler.stelekit.ui.components.settings.BackgroundTaskGraph
import dev.stapler.stelekit.ui.components.settings.BackgroundTasksSection
import dev.stapler.stelekit.ui.components.settings.NO_REMOTE_REASON
import dev.stapler.stelekit.ui.components.settings.REVIEW_FIRST_SYNC_REASON
import dev.stapler.stelekit.ui.components.settings.SWITCH_LABEL
import dev.stapler.stelekit.ui.components.settings.ScheduledSyncSection
import dev.stapler.stelekit.ui.components.settings.ScheduledSyncSettingsState
import dev.stapler.stelekit.ui.components.settings.SwitchGate
import dev.stapler.stelekit.ui.components.settings.TaskSettingsPanel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 4.1.1 gaps closed in 4.1.2: UX-14 (disabled-switch reasons in the per-graph panel), UX-20
 * (every error state has an exit), UX-24 (Tab reaches every row), UX-26 (one focus stop per row).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w400dp-h2400dp")
class TaskSettingsAccessibilityAndErrorsTest {
    @get:Rule
    val rule = createComposeRule()

    private val graphs = listOf(BackgroundTaskGraph("g1", "Work notes"), BackgroundTaskGraph("g2", "Graph 2"))
    private val toggleable = SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)

    private fun showPanel(fixture: BackgroundTaskFixture, gate: SwitchGate? = null) {
        rule.setContent {
            MaterialTheme { TaskSettingsPanel(fixture.controller, fixture.gitKey("g1"), "Git sync - Work notes", switchGate = gate) }
        }
        rule.waitForIdle()
    }

    // ---- UX-14 ----

    @Test
    fun panel_should_disableSwitchWithReasonAndSetUpAction_When_NoRemote() {
        var fixes = 0
        showPanel(BackgroundTaskFixture(BackgroundTaskFixture.ANDROID), SwitchGate.NoRemote { fixes++ })
        rule.onNodeWithText(NO_REMOTE_REASON).assertExists()
        rule.onNode(toggleable).assertIsNotEnabled()
        assertEquals("Disabled, $NO_REMOTE_REASON", rule.onNode(toggleable).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        rule.onNodeWithText("Set up Git sync").performClick()
        assertEquals(1, fixes)
    }

    @Test
    fun panel_should_disableSwitchWithReasonAndReviewAction_When_FirstSyncUnreviewed() {
        var fixes = 0
        showPanel(BackgroundTaskFixture(BackgroundTaskFixture.ANDROID), SwitchGate.FirstSyncReview { fixes++ })
        rule.onNodeWithText(REVIEW_FIRST_SYNC_REASON).assertExists()
        rule.onNode(toggleable).assertIsNotEnabled()
        rule.onNodeWithText("Review first sync").performClick()
        assertEquals(1, fixes)
    }

    @Test
    fun panel_should_keepAnAlreadyOnSwitchOperable_When_GateAppearsAfterEnabling() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        kotlinx.coroutines.runBlocking {
            fixture.store.write(fixture.gitKey("g1"), dev.stapler.stelekit.jobs.schedule.TaskEntry(true, null))
        }
        showPanel(fixture, SwitchGate.NoRemote {})
        rule.onNode(toggleable).assertIsEnabled()
        rule.onNodeWithText(NO_REMOTE_REASON).assertDoesNotExist()
    }

    @Test
    fun panel_should_showNoReasonAndKeepSwitchEnabled_When_NoGateKnown() {
        // A non-active graph's remote status is unknowable (single-open config repository): no gate, no reason.
        showPanel(BackgroundTaskFixture(BackgroundTaskFixture.ANDROID), gate = null)
        rule.onNode(toggleable).assertIsEnabled()
        rule.onNodeWithText(NO_REMOTE_REASON).assertDoesNotExist()
    }

    @Test
    fun section_should_passGateOnlyToTheGraphItIsKnownFor_When_DrillingIntoGraphs() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        rule.setContent {
            MaterialTheme {
                BackgroundTasksSection(
                    fixture.controller, graphs, onAddGraph = {},
                    switchGate = { _, id -> if (id == "g1") SwitchGate.NoRemote {} else null },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Git sync", substring = true).performClick()
        rule.onNodeWithText("Work notes", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(NO_REMOTE_REASON).assertExists()
        rule.onNodeWithText("Back").performClick()
        rule.onNodeWithText("Graph 2", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(NO_REMOTE_REASON).assertDoesNotExist()
        rule.onNode(toggleable).assertIsEnabled()
    }

    // ---- UX-20 ----

    /** Enabled, clickable nodes the user can act on: Retry, Back, a fix action, or a control to choose again. */
    private fun exits() = rule.onAllNodes(hasClickAction() and isEnabled()).fetchSemanticsNodes().size

    private fun legacyState(
        hasRemote: Boolean = true,
        reviewRequired: Boolean = false,
        status: ScheduledSyncStatus = ScheduledSyncStatus(),
        unreadable: Boolean = false,
        lateness: ScheduleLateness = ScheduleLateness.OnTime,
    ) = ScheduledSyncSettingsState(
        hasRemote = hasRemote, enabled = false, configuredIntervalMinutes = 60, minimumIntervalMinutes = 15,
        closedAppChecks = true, backgroundRestricted = false, status = status,
        onEnabledChange = {}, onIntervalChange = {}, onResume = {}, onSetUpGit = {}, onFix = {},
        firstSyncReviewRequired = reviewRequired, lateness = lateness, settingsUnreadable = unreadable,
        capabilities = BackgroundTaskFixture.ANDROID, restriction = RestrictionReason.None,
    ).let { if (it.status.paused || unreadable) it.copy(enabled = true) else it }

    @Test
    fun errorStates_should_alwaysOfferAnExit_When_AnyS1ToS4FailureRenders() {
        val scenarios = mutableListOf<Pair<String, Pair<String, String>>>()
        scenarios += "no remote" to (NO_REMOTE_REASON to "Set up Git sync")
        scenarios += "first sync unreviewed" to (REVIEW_FIRST_SYNC_REASON to "Review first sync")
        scenarios += "paused" to ("Paused after 3 failures - tap to fix" to "Resume")
        scenarios += "settings unreadable" to (SETTINGS_UNREADABLE_TEXT to "Retry")
        val states = mapOf(
            "no remote" to legacyState(hasRemote = false),
            "first sync unreviewed" to legacyState(reviewRequired = true),
            "paused" to legacyState(status = ScheduledSyncStatus(paused = true, consecutiveFailures = 3, lastFailure = "auth")),
            "settings unreadable" to legacyState(unreadable = true, lateness = ScheduleLateness.Late(3_600_000L, LateCause.Unattributed)),
        )
        // One composition, state swapped per scenario.
        var current by mutableStateOf(states.getValue("no remote"))
        rule.setContent { MaterialTheme { ScheduledSyncSection(current, nowMs = 50_000_000L) } }
        for ((name, expect) in scenarios) {
            rule.runOnIdle { current = states.getValue(name) }
            rule.waitForIdle()
            rule.onNodeWithText(expect.first).assertExists()
            rule.onNode(hasText(expect.second) and hasClickAction() and isEnabled()).assertExists()
        }
    }

    @Test
    fun errorStates_should_offerRetryAndBack_When_S2ReadFails() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        rule.setContent { MaterialTheme { BackgroundTasksSection(fixture.controller, graphs, {}) } }
        rule.waitForIdle()
        rule.onNodeWithText("Git sync", substring = true).performClick()
        rule.waitForIdle()
        fixture.settings.failReads = true
        rule.onNodeWithText("Work notes", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(COULDNT_READ_SETTINGS).assertExists()
        rule.onNode(hasText("Retry") and hasClickAction() and isEnabled()).assertExists()
        rule.onNode(hasText("Back") and hasClickAction() and isEnabled()).assertExists()
        fixture.settings.failReads = false
        rule.onNodeWithText("Retry").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(COULDNT_READ_SETTINGS).assertDoesNotExist()
        rule.onNodeWithText(SWITCH_LABEL).assertExists()
    }

    @Test
    fun errorStates_should_leaveControlsOperableAndBackAvailable_When_S2SaveFails() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        rule.setContent { MaterialTheme { BackgroundTasksSection(fixture.controller, graphs, {}) } }
        rule.waitForIdle()
        rule.onNodeWithText("Git sync", substring = true).performClick()
        rule.onNodeWithText("Work notes", substring = true).performClick()
        rule.waitForIdle()
        fixture.settings.failWrites = true
        rule.onNodeWithText("2 hours").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(COULDNT_SAVE).assertExists()
        // Retry by choosing again: the control is still enabled; Back is still there.
        rule.onNode(hasText("2 hours") and hasClickAction() and isEnabled()).assertExists()
        rule.onNode(hasText("Back") and hasClickAction() and isEnabled()).assertExists()
    }

    @Test
    fun errorStates_should_offerBack_When_S2IsLoading() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID, seed = { gate.await(); null })
        rule.setContent { MaterialTheme { BackgroundTasksSection(fixture.controller, graphs, {}) } }
        rule.waitForIdle()
        rule.onNodeWithText("Git sync", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Work notes", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(LOADING_SETTINGS_REASON).assertExists()
        rule.onNode(hasText("Back") and hasClickAction() and isEnabled()).assertExists()
        gate.complete(Unit)
    }

    @Test
    fun errorStates_should_offerAddGraph_When_NoGraphsYet() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        rule.setContent { MaterialTheme { BackgroundTasksSection(fixture.controller, emptyList(), {}) } }
        rule.waitForIdle()
        rule.onNode(hasText("Add graph") and hasClickAction() and isEnabled()).assertExists()
        assertTrue(exits() >= 1)
    }

    // ---- UX-24 ----

    private fun focusedText(): String? =
        rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun keyboard_should_tabToEveryInteractiveRowInOrder_When_PanelRendered() {
        showPanel(BackgroundTaskFixture(BackgroundTaskFixture.ANDROID))
        val expected = listOf(SWITCH_LABEL, "15 min", "30 min", "1 hour", "2 hours", "6 hours", "Daily", "Any network", "Unmetered networks only", "Advanced")
        val visited = mutableListOf<String>()
        repeat(expected.size) {
            rule.onRoot().performKeyInput { pressKey(Key.Tab) }
            rule.waitForIdle()
            visited += focusedText().orEmpty()
        }
        expected.forEachIndexed { i, label ->
            assertTrue(visited[i].startsWith(label), "tab stop ${i + 1} should be '$label' but was '${visited[i]}'; all stops: $visited")
        }
    }

    // ---- UX-26 ----

    @Test
    fun rows_should_haveExactlyOneRoleNodePerOption_AndNoFocusableInnerControl() {
        showPanel(BackgroundTaskFixture(BackgroundTaskFixture.ANDROID))
        // Android lists 6 interval presets (no 5 min) and 2 network options; the switch is one node.
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertCountEquals(8)
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertCountEquals(1)
        // Every focus stop is one of those row nodes (plus the title and the Advanced button): nothing nested.
        val stops = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)).fetchSemanticsNodes()
        val labelled = stops.mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
        assertEquals(labelled.size, labelled.toSet().size, "a label appears on two focus stops: $labelled")
    }
}
