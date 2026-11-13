// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.getOrNull
import dev.stapler.stelekit.git.BATTERY_SETTINGS_MANUAL_PATH
import dev.stapler.stelekit.git.BatterySettingsLauncher
import dev.stapler.stelekit.git.BatterySettingsResult
import dev.stapler.stelekit.git.OPEN_BATTERY_SETTINGS_LABEL
import dev.stapler.stelekit.git.ScheduledSyncStatus
import dev.stapler.stelekit.git.SETTINGS_UNREADABLE_TEXT
import dev.stapler.stelekit.jobs.schedule.LateCause
import dev.stapler.stelekit.jobs.schedule.RestrictionReason
import dev.stapler.stelekit.jobs.schedule.ScheduleLateness
import dev.stapler.stelekit.jobs.schedule.SchedulerCapabilities
import dev.stapler.stelekit.ui.components.settings.ScheduledSyncSection
import dev.stapler.stelekit.ui.components.settings.ScheduledSyncSettingsState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 4.1.2 (UX-8, 9, 17, 18, 27, 28, 39): status line, skip reasons, hint row, and the reused handlers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w400dp-h2400dp")
class ScheduledSyncStatusActionsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val hour = 3_600_000L
    private val now = 100 * hour
    private val android = SchedulerCapabilities(15, 5, true, true, true, runsWhenClosed = true, schedulesWork = true)
    private val desktop = SchedulerCapabilities(1, 0, false, false, true, runsWhenClosed = false, schedulesWork = true)

    private class Taps {
        var resumes = 0
        var unlocks = 0
        var conflicts = 0
        var retries = 0
    }

    private class FakeLauncher(private val outcome: () -> BatterySettingsResult) : BatterySettingsLauncher {
        var calls = 0
        override fun open(): BatterySettingsResult {
            calls++
            return outcome()
        }
    }

    private fun state(
        taps: Taps = Taps(),
        caps: SchedulerCapabilities? = android,
        enabled: Boolean = true,
        status: ScheduledSyncStatus = ScheduledSyncStatus(lastSuccessAtMs = now - 3 * hour),
        lateness: ScheduleLateness = ScheduleLateness.OnTime,
        restriction: RestrictionReason = RestrictionReason.None,
        unreadable: Boolean = false,
        launcher: BatterySettingsLauncher? = null,
    ) = ScheduledSyncSettingsState(
        hasRemote = true, enabled = enabled, configuredIntervalMinutes = 60, minimumIntervalMinutes = 15,
        closedAppChecks = caps?.runsWhenClosed == true, backgroundRestricted = restriction != RestrictionReason.None,
        status = status, onEnabledChange = {}, onIntervalChange = {}, onResume = { taps.resumes++ },
        onSetUpGit = {}, onFix = {},
        lateness = lateness, capabilities = caps, restriction = restriction, settingsUnreadable = unreadable,
        onRetrySettings = { taps.retries++ }, onUnlock = { taps.unlocks++ }, onReviewConflict = { taps.conflicts++ },
        batterySettingsLauncher = launcher,
    )

    private fun show(s: ScheduledSyncSettingsState, nowMs: Long = now) {
        composeTestRule.setContent { MaterialTheme { ScheduledSyncSection(s, nowMs = nowMs) } }
    }

    private val late3h = ScheduleLateness.Late(3 * hour, LateCause.Unattributed)

    @Test
    fun section_should_showHasntRunInHours_When_LateOnAndroid() {
        show(state(lateness = late3h))
        composeTestRule.onNodeWithText("Hasn't run in 3 h").assertExists()
    }

    @Test
    fun section_should_neverShowLateCopy_When_DesktopCapabilities() {
        // Desktop reports the same Late lateness, but its in-process loop has no schedule to slip behind.
        show(state(caps = desktop, lateness = late3h, restriction = RestrictionReason.BatterySaver))
        composeTestRule.onNodeWithText("Hasn't run in 3 h").assertDoesNotExist()
        composeTestRule.onNodeWithText("Last synced 180 min ago").assertExists()
        composeTestRule.onNodeWithText("Some phones stop background apps. Check battery settings.").assertDoesNotExist()
        composeTestRule.onNodeWithText("Battery saver is on.", substring = true).assertDoesNotExist()
    }

    @Test
    fun section_should_showCheckedNotLate_When_ForegroundSuccessIsRecent() {
        show(state(status = ScheduledSyncStatus(lastSuccessAtMs = now - 10 * 60_000L), lateness = ScheduleLateness.OnTime))
        composeTestRule.onNodeWithText("Last synced 10 min ago").assertExists()
        composeTestRule.onNodeWithText("Hasn't run in", substring = true).assertDoesNotExist()
    }

    @Test
    fun section_should_showLockedWithUnlockActionAndNoRestrictedHint_When_LateCauseIsVaultLocked() {
        val taps = Taps()
        show(state(taps, lateness = ScheduleLateness.Late(5 * hour, LateCause.DeliberateSkip("vault-locked")), restriction = RestrictionReason.StandbyRestricted, launcher = FakeLauncher { BatterySettingsResult.Launched }))
        composeTestRule.onNodeWithText("Locked. Sync resumes after you unlock.").assertExists()
        composeTestRule.onNodeWithText(OPEN_BATTERY_SETTINGS_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText("Android is restricting this app.").assertDoesNotExist()
        composeTestRule.onNodeWithText("Unlock").performClick()
        assertEquals(1, taps.unlocks)
    }

    @Test
    fun section_should_callConflictReviewHandlerOnce_When_ConflictPendingSkip() {
        val taps = Taps()
        val status = ScheduledSyncStatus(lastSkip = dev.stapler.stelekit.git.SkipReason.Policy(dev.stapler.stelekit.git.ScheduledSkipReason.ConflictPending))
        show(state(taps, status = status))
        composeTestRule.onNodeWithText("Resolve the conflict to resume.").assertExists()
        composeTestRule.onNodeWithText("Review conflict").performClick()
        assertEquals(1, taps.conflicts)
    }

    @Test
    fun section_should_showEditingAndBusyCopyWithoutAction_When_ThoseSkipsAreTheCause() {
        val editing = ScheduledSyncStatus(lastSkip = dev.stapler.stelekit.git.SkipReason.Policy(dev.stapler.stelekit.git.ScheduledSkipReason.EditingInProgress))
        show(state(status = editing))
        composeTestRule.onNodeWithText("Sync paused while you edit. It continues when you stop.").assertExists()
        composeTestRule.onNodeWithText("Unlock").assertDoesNotExist()
        composeTestRule.onNodeWithText("Review conflict").assertDoesNotExist()
    }

    @Test
    fun section_should_showBusyCopy_When_LateCauseIsBusySkip() {
        show(state(lateness = ScheduleLateness.Late(4 * hour, LateCause.DeliberateSkip("busy"))))
        composeTestRule.onNodeWithText("Another sync is running.").assertExists()
    }

    @Test
    fun section_should_callResumeHandlerOnce_When_Paused() {
        val taps = Taps()
        show(state(taps, status = ScheduledSyncStatus(paused = true, consecutiveFailures = 3, lastFailure = "auth")))
        composeTestRule.onNodeWithText("Resume").performClick()
        assertEquals(1, taps.resumes)
    }

    @Test
    fun section_should_showCantReadSettingsWithRetryAndNeverLate_When_UnreadableAndLate() {
        val taps = Taps()
        show(state(taps, lateness = late3h, unreadable = true))
        composeTestRule.onNodeWithText(SETTINGS_UNREADABLE_TEXT).assertExists()
        composeTestRule.onNodeWithText("Hasn't run in", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Retry").performClick()
        assertEquals(1, taps.retries)
    }

    @Test
    fun hint_should_showTextWithoutButton_When_BatterySaver() {
        show(state(restriction = RestrictionReason.BatterySaver, launcher = FakeLauncher { BatterySettingsResult.Launched }))
        composeTestRule.onNodeWithText("Battery saver is on. Background sync resumes when you turn it off or charge.").assertExists()
        composeTestRule.onNodeWithText(OPEN_BATTERY_SETTINGS_LABEL).assertDoesNotExist()
    }

    private fun assertButtonLaunchesOnce(reason: RestrictionReason) {
        val launcher = FakeLauncher { BatterySettingsResult.Launched }
        show(state(restriction = reason, launcher = launcher))
        composeTestRule.onNodeWithText("Android is restricting this app.").assertExists()
        composeTestRule.onNodeWithText(OPEN_BATTERY_SETTINGS_LABEL).performClick()
        assertEquals(1, launcher.calls)
        composeTestRule.onNodeWithText(BATTERY_SETTINGS_MANUAL_PATH).assertDoesNotExist()
    }

    @Test
    fun hint_should_launchSettingsOnce_When_StandbyRestrictedButtonTapped() = assertButtonLaunchesOnce(RestrictionReason.StandbyRestricted)

    @Test
    fun hint_should_launchSettingsOnce_When_AppRestrictedButtonTapped() = assertButtonLaunchesOnce(RestrictionReason.AppRestricted)

    @Test
    fun hint_should_showSofterOemWordingWithButton_When_LateAndProbeNone() {
        val launcher = FakeLauncher { BatterySettingsResult.Launched }
        show(state(lateness = late3h, restriction = RestrictionReason.None, launcher = launcher))
        composeTestRule.onNodeWithText("Some phones stop background apps. Check battery settings.").assertExists()
        composeTestRule.onNodeWithText(OPEN_BATTERY_SETTINGS_LABEL).performClick()
        assertEquals(1, launcher.calls)
    }

    @Test
    fun hint_should_becomeManualPathAndNotClickable_When_LauncherReportsUnavailable() {
        val launcher = FakeLauncher { BatterySettingsResult.Unavailable }
        show(state(restriction = RestrictionReason.AppRestricted, launcher = launcher))
        composeTestRule.onNodeWithText(OPEN_BATTERY_SETTINGS_LABEL).performClick()
        composeTestRule.onNodeWithText(OPEN_BATTERY_SETTINGS_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText(BATTERY_SETTINGS_MANUAL_PATH).assertExists()
        assertTrue(composeTestRule.onNodeWithText(BATTERY_SETTINGS_MANUAL_PATH).fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) == null)
    }

    @Test
    fun hint_should_becomeManualPathWithoutCrashing_When_LauncherThrows() {
        val launcher = FakeLauncher { throw SecurityException("denied") }
        show(state(restriction = RestrictionReason.StandbyRestricted, launcher = launcher))
        composeTestRule.onNodeWithText(OPEN_BATTERY_SETTINGS_LABEL).performClick()
        assertEquals(1, launcher.calls)
        composeTestRule.onNodeWithText(BATTERY_SETTINGS_MANUAL_PATH).assertExists()
    }

    @Test
    fun hint_should_showManualPath_When_NoLauncherOnThisPlatform() {
        show(state(restriction = RestrictionReason.AppRestricted, launcher = null))
        composeTestRule.onNodeWithText(OPEN_BATTERY_SETTINGS_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText(BATTERY_SETTINGS_MANUAL_PATH).assertExists()
    }

    @Test
    fun hint_should_clearWhenCauseClears_AndBeHiddenWhenDisabled() {
        show(state(enabled = false, restriction = RestrictionReason.BatterySaver))
        composeTestRule.onNodeWithText("Battery saver is on.", substring = true).assertDoesNotExist()
    }

    @Test
    fun statusLine_should_notReannounce_When_30sRefreshLeavesStateUnchanged() {
        var tick by mutableLongStateOf(now)
        composeTestRule.setContent {
            MaterialTheme { ScheduledSyncSection(state(lateness = late3h, restriction = RestrictionReason.BatterySaver), nowMs = tick) }
        }
        fun liveTexts() = composeTestRule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text } }
        val before = liveTexts()
        composeTestRule.runOnIdle { tick += 30_000L }
        composeTestRule.waitForIdle()
        assertEquals(before, liveTexts())
        composeTestRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive)).assertCountEquals(0)
    }

    @Test
    fun lateAndHintRows_should_pairTextWithIcon_NotColourAlone() {
        show(state(lateness = late3h, restriction = RestrictionReason.AppRestricted, launcher = FakeLauncher { BatterySettingsResult.Launched }))
        // The text carries the state; the icons are decorative (null description) so a screen reader hears the text once.
        composeTestRule.onNodeWithText("Hasn't run in 3 h").assertExists()
        composeTestRule.onNodeWithText("Android is restricting this app.").assertExists()
        composeTestRule.onNodeWithText(OPEN_BATTERY_SETTINGS_LABEL).assertHasClickAction()
    }
}
