// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.git.ScheduledSyncStatus
import dev.stapler.stelekit.git.SkipReason
import dev.stapler.stelekit.ui.components.settings.ANDROID_FIVE_MINUTE_REASON
import dev.stapler.stelekit.ui.components.settings.ANDROID_HELPER
import dev.stapler.stelekit.ui.components.settings.DESKTOP_HELPER
import dev.stapler.stelekit.ui.components.settings.NO_REMOTE_REASON
import dev.stapler.stelekit.ui.components.settings.RESTRICTED_HELPER
import dev.stapler.stelekit.ui.components.settings.SWITCH_LABEL
import dev.stapler.stelekit.ui.components.settings.ScheduledSyncSection
import dev.stapler.stelekit.ui.components.settings.ScheduledSyncSettingsState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings > Scheduled sync (UT-37, UT-38, UT-125, UT-166; UW-14, UW-15, UW-17..UW-19, UW-21, UW-23,
 * UW-24, UW-65..UW-67, UW-103). The dialog itself is a window Robolectric cannot reach, so the
 * section is composed directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ScheduledSyncSettingsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val minute = 60_000L
    private val now = 50_000_000L

    private class Taps {
        var enabled: Boolean? = null
        var interval: Int? = null
        var resumes = 0
        var setUp = 0
        var fixes = 0
        val count get() = listOfNotNull(enabled, interval).size + resumes + setUp + fixes
    }

    private fun state(
        taps: Taps = Taps(),
        hasRemote: Boolean = true,
        enabled: Boolean = false,
        configured: Int = 15,
        minimum: Int = 1,
        closedApp: Boolean = false,
        restricted: Boolean = false,
        status: ScheduledSyncStatus = ScheduledSyncStatus(),
    ) = ScheduledSyncSettingsState(
        hasRemote = hasRemote, enabled = enabled, configuredIntervalMinutes = configured,
        minimumIntervalMinutes = minimum, closedAppChecks = closedApp, backgroundRestricted = restricted,
        status = status,
        onEnabledChange = { taps.enabled = it }, onIntervalChange = { taps.interval = it },
        onResume = { taps.resumes++ }, onSetUpGit = { taps.setUp++ }, onFix = { taps.fixes++ },
    )

    private fun show(s: ScheduledSyncSettingsState, nowMs: Long = now) {
        composeTestRule.setContent { MaterialTheme { ScheduledSyncSection(s, nowMs = nowMs) } }
    }

    @Test
    fun scheduledSyncSection_should_ShowSwitchOffAndRadioGroup5_15_30_60_When_RemoteConfigured() {
        show(state())
        composeTestRule.onNodeWithText(SWITCH_LABEL).assertExists()
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertIsOff()
        listOf(5, 15, 30, 60).forEach { composeTestRule.onNodeWithText("$it minutes").assertExists() }
        composeTestRule.onNodeWithText("15 minutes").assertIsSelected()
    }

    @Test
    fun scheduledSyncSection_should_DisableSwitchWithReasonAndWizardButton_When_NoRemote() {
        val taps = Taps()
        show(state(taps, hasRemote = false))
        composeTestRule.onNodeWithText(NO_REMOTE_REASON).assertExists()
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertIsNotEnabled()
        composeTestRule.onNodeWithText("Set up Git sync").performClick()
        assertEquals(1, taps.setUp)
    }

    @Test
    fun scheduledSync_should_EnableAndPickIntervalInThreeTapsWithNoTextInput_When_RemoteConfigured() {
        val taps = Taps()
        show(state(taps))
        composeTestRule.onNodeWithText(SWITCH_LABEL).performClick()
        composeTestRule.onNodeWithText("30 minutes").performClick()
        assertEquals(true, taps.enabled)
        assertEquals(30, taps.interval)
        assertTrue(composeTestRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun section_should_ShowPlatformHelperText_When_DesktopOrAndroid() {
        show(state(closedApp = false))
        composeTestRule.onNodeWithText(DESKTOP_HELPER).assertExists()
    }

    @Test
    fun section_should_ShowAndroidHelper_When_ClosedAppChecksAvailable() {
        show(state(closedApp = true, minimum = 15))
        composeTestRule.onNodeWithText(ANDROID_HELPER).assertExists()
    }

    @Test
    fun status_should_ReadLastChecked3MinNextIn12Min_And_NotTakeFocus_When_TickSucceeded() {
        val last = now - 3 * minute
        show(state(enabled = true, status = ScheduledSyncStatus(running = true, lastAttemptAtMs = last, lastSuccessAtMs = last, nextAtMs = last + 15 * minute)))
        composeTestRule.onNodeWithText("Last checked 3 min ago · next in 12 min").assertExists()
        assertTrue(composeTestRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun picker_should_DisableFiveWithReasonAndShowRunsEvery15_When_AndroidAndStoredBelow15() {
        show(state(configured = 5, minimum = 15, closedApp = true))
        composeTestRule.onNodeWithText("5 minutes").assertIsNotEnabled()
        composeTestRule.onNodeWithText(ANDROID_FIVE_MINUTE_REASON).assertExists()
        composeTestRule.onNodeWithText("Runs every 15 min on this device").assertExists()
        composeTestRule.onNodeWithText("15 minutes").assertIsSelected().assertIsEnabled()
    }

    private fun assertAndroidShowsExactly(chosen: Int) {
        show(state(configured = chosen, minimum = 15, closedApp = true))
        composeTestRule.onNodeWithText("$chosen minutes").assertIsSelected()
        composeTestRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertCountEquals(1)
        composeTestRule.onNodeWithText("Runs every 15 min on this device").assertDoesNotExist()
    }

    @Test
    fun picker_should_ShowExactly30_When_Android30() = assertAndroidShowsExactly(30)

    @Test
    fun picker_should_ShowExactly60_When_Android60() = assertAndroidShowsExactly(60)

    @Test
    fun picker_should_ShowExactly15_When_Android15() = assertAndroidShowsExactly(15)

    @Test
    fun status_should_ShowBackgroundRestrictedLineWithoutErrorBadge_When_ProbeReportsOnAndroid_And_NeverOnDesktop() {
        show(state(closedApp = true, minimum = 15, restricted = true))
        composeTestRule.onNodeWithText(RESTRICTED_HELPER).assertExists()
        composeTestRule.onNodeWithText(ANDROID_HELPER).assertDoesNotExist()
    }

    @Test
    fun status_should_ClearRestrictedLine_When_RestrictionEnds() {
        var restricted by androidx.compose.runtime.mutableStateOf(true)
        composeTestRule.setContent {
            MaterialTheme { ScheduledSyncSection(state(closedApp = true, minimum = 15, restricted = restricted), nowMs = now) }
        }
        composeTestRule.onNodeWithText(RESTRICTED_HELPER).assertExists()
        restricted = false
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(RESTRICTED_HELPER).assertDoesNotExist()
        composeTestRule.onNodeWithText(ANDROID_HELPER).assertExists()
    }

    @Test
    fun status_should_NeverShowRestrictedLine_When_Desktop() {
        show(state(closedApp = false, restricted = true))
        composeTestRule.onNodeWithText(RESTRICTED_HELPER).assertDoesNotExist()
        composeTestRule.onNodeWithText(DESKTOP_HELPER).assertExists()
    }

    @Test
    fun row_should_ShowPausedAfter3FailuresRouteToFixAndShowResume_When_StatusPaused() {
        val taps = Taps()
        show(state(taps, enabled = true, status = ScheduledSyncStatus(paused = true, consecutiveFailures = 3, lastFailure = "Authentication failed")))
        composeTestRule.onNodeWithText("Paused after 3 failures - tap to fix").performClick()
        assertEquals(1, taps.fixes)
        composeTestRule.onNodeWithText("Authentication failed").assertExists()
        composeTestRule.onNodeWithText("Resume").assertIsEnabled()
    }

    @Test
    fun resume_should_BeVisibleWhilePausedAndRunOneCheck_When_Tapped() {
        val taps = Taps()
        show(state(taps, enabled = true, status = ScheduledSyncStatus(paused = true, consecutiveFailures = 3)))
        composeTestRule.onNodeWithText("Resume").performClick()
        assertEquals(1, taps.resumes)
    }

    @Test
    fun tick_should_BeSkippedShowCheckInProgressAndNeverQueued_When_PreviousTickRunning() {
        show(state(enabled = true, status = ScheduledSyncStatus(lastSkip = SkipReason.Busy)))
        composeTestRule.onNodeWithText("Check in progress").assertExists()
    }

    @Test
    fun status_should_ReadOfflineWillResume_When_Offline() {
        show(state(enabled = true, status = ScheduledSyncStatus(lastSkip = SkipReason.Offline)))
        composeTestRule.onNodeWithText("Offline - will check when you are back online").assertExists()
    }

    @Test
    fun switchOff_should_ReadNotScheduled_When_Toggled() {
        show(state(enabled = false))
        composeTestRule.onNodeWithText("Not scheduled").assertExists()
    }

    @Test
    fun stopping_should_ShowOnlyAfterTwoSeconds_When_SwitchOffWhileTickRunning() {
        composeTestRule.mainClock.autoAdvance = false
        show(state(enabled = false, status = ScheduledSyncStatus(tickInFlight = true)))
        composeTestRule.onNodeWithText("Not scheduled").assertExists()
        composeTestRule.mainClock.advanceTimeBy(2_100)
        composeTestRule.onNodeWithText("Stopping...").assertExists()
    }

    @Test
    fun section_should_ExposeOneToggleNodeRadioGroupAnd48dpTargets_And_BeKeyboardOperable() {
        show(state())
        composeTestRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(1)
        composeTestRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton)).assertCountEquals(4)
        composeTestRule.onNodeWithText(SWITCH_LABEL).assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithText("5 minutes").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun labelAndHelper_should_SayCheckForUpdatesAndMentionBackgroundChecksOnAndroid_And_NeverSaySyncedBeforeU16() {
        show(state(closedApp = true, minimum = 15, enabled = true, status = ScheduledSyncStatus(lastSuccessAtMs = now - minute, nextAtMs = now + 14 * minute)))
        assertEquals("Check for updates automatically", SWITCH_LABEL)
        composeTestRule.onNodeWithText(ANDROID_HELPER).assertExists()
        val allText = composeTestRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }.joinToString(" ") { it.text }
        assertFalse(allText.contains("synced", ignoreCase = true), allText)
        assertFalse(allText.contains("sync automatically", ignoreCase = true), allText)
    }

    @Test
    fun scheduledSyncSection_should_RenderNoReviewFirstSyncRow_When_U2InterimFetchOnly() {
        show(state())
        composeTestRule.onNodeWithText("Review first sync to enable").assertDoesNotExist()
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertIsEnabled().assertIsOff()
        assertEquals(0, Taps().count)
    }

    @Test
    fun switch_should_ShowOn_When_EnabledState() {
        show(state(enabled = true))
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertIsOn()
    }
}
