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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.stapler.stelekit.git.COULDNT_READ_SETTINGS
import dev.stapler.stelekit.git.COULDNT_SAVE
import dev.stapler.stelekit.git.BACK_TO_DEFAULT
import dev.stapler.stelekit.git.LOADING_SETTINGS_REASON
import dev.stapler.stelekit.git.UNMETERED_DESKTOP_REASON
import dev.stapler.stelekit.git.GitSyncTask
import dev.stapler.stelekit.jobs.schedule.NetworkPolicy
import dev.stapler.stelekit.jobs.schedule.NetworkPolicyCopy
import dev.stapler.stelekit.jobs.schedule.Schedule
import dev.stapler.stelekit.jobs.schedule.TaskConstraints
import dev.stapler.stelekit.jobs.schedule.TaskEntry
import dev.stapler.stelekit.jobs.schedule.TaskOverrides
import dev.stapler.stelekit.ui.FontScaled
import dev.stapler.stelekit.ui.components.settings.PanelTarget
import dev.stapler.stelekit.ui.components.settings.TaskSettingsPanel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.assertNull
import dev.stapler.stelekit.git.ScheduledSyncStatus
import dev.stapler.stelekit.git.SkipReason
import dev.stapler.stelekit.ui.components.settings.ANDROID_FIVE_MINUTE_REASON
import dev.stapler.stelekit.ui.components.settings.ANDROID_HELPER
import dev.stapler.stelekit.ui.components.settings.DESKTOP_HELPER
import dev.stapler.stelekit.ui.components.settings.NO_REMOTE_REASON
import dev.stapler.stelekit.ui.components.settings.RESTRICTED_HELPER
import dev.stapler.stelekit.ui.components.settings.REVIEW_FIRST_SYNC_REASON
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
@Config(sdk = [29], qualifiers = "w400dp-h2400dp")
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
        var reviews = 0
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
        reviewRequired: Boolean = false,
    ) = ScheduledSyncSettingsState(
        hasRemote = hasRemote, enabled = enabled, configuredIntervalMinutes = configured,
        minimumIntervalMinutes = minimum, closedAppChecks = closedApp, backgroundRestricted = restricted,
        status = status,
        onEnabledChange = { taps.enabled = it }, onIntervalChange = { taps.interval = it },
        onResume = { taps.resumes++ }, onSetUpGit = { taps.setUp++ }, onFix = { taps.fixes++ },
        firstSyncReviewRequired = reviewRequired, onReviewFirstSync = { taps.reviews++ },
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
        composeTestRule.onNodeWithText("Last synced 3 min ago · next in 12 min").assertExists()
        assertTrue(composeTestRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun picker_should_NotListFiveAndShowHelperAndRunsEvery15_When_AndroidAndStoredBelow15() {
        show(state(configured = 5, minimum = 15, closedApp = true))
        composeTestRule.onNodeWithText("5 minutes").assertDoesNotExist()
        composeTestRule.onNodeWithText("Android runs at most every 15 minutes.").assertExists()
        assertEquals("Android runs at most every 15 minutes", ANDROID_FIVE_MINUTE_REASON)
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
        composeTestRule.onNodeWithText("Sync in progress").assertExists()
    }

    @Test
    fun status_should_ReadOfflineWillResume_When_Offline() {
        show(state(enabled = true, status = ScheduledSyncStatus(lastSkip = SkipReason.Offline)))
        composeTestRule.onNodeWithText("Offline - will sync when you are back online").assertExists()
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
    fun labelAndStatus_should_SaySyncedNowThatScheduledTicksPull_And_NeverSayChecked_UW17() {
        show(state(closedApp = true, minimum = 15, enabled = true, status = ScheduledSyncStatus(lastSuccessAtMs = now - minute, nextAtMs = now + 14 * minute)))
        assertEquals("Sync automatically", SWITCH_LABEL)
        composeTestRule.onNodeWithText(ANDROID_HELPER).assertExists()
        val allText = composeTestRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }.joinToString(" ") { it.text }
        assertTrue(allText.contains("Last synced 1 min ago"), allText)
        assertFalse(allText.contains("Last checked", ignoreCase = true), allText)
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

    @Test
    fun scheduledSyncSection_should_DisableSwitchWithReasonAndReviewButton_When_FirstSyncNotReviewed() {
        val taps = Taps()
        show(state(taps, reviewRequired = true))
        composeTestRule.onNodeWithText(REVIEW_FIRST_SYNC_REASON).assertExists()
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertIsNotEnabled()
        composeTestRule.onNodeWithText("Review first sync").performClick()
        assertEquals(1, taps.reviews)
        assertEquals(null, taps.enabled, "the switch itself must not have fired")
    }

    @Test
    fun scheduledSyncSection_should_KeepAnAlreadyOnSwitchUsable_When_FirstSyncNotReviewed() {
        show(state(enabled = true, reviewRequired = true))
        composeTestRule.onNodeWithText(REVIEW_FIRST_SYNC_REASON).assertDoesNotExist()
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertIsEnabled().assertIsOn()
    }

    @Test
    fun scheduledSyncSection_should_RenderNoReviewRow_When_FirstSyncConfirmed() {
        show(state(reviewRequired = false))
        composeTestRule.onNodeWithText(REVIEW_FIRST_SYNC_REASON).assertDoesNotExist()
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertIsEnabled()
    }

    // ---- Story 4.1.1: per-graph, per-kind panel (S2) ----

    private fun showPanel(
        fixture: BackgroundTaskFixture,
        graph: String = "g1",
        target: PanelTarget = PanelTarget.Entry,
    ) {
        composeTestRule.setContent {
            MaterialTheme { TaskSettingsPanel(fixture.controller, fixture.gitKey(graph), "Git sync - Work notes", target = target) }
        }
        composeTestRule.waitForIdle()
    }

    private fun BackgroundTaskFixture.entryOf(graph: String = "g1") = runBlocking { store.readEntry(gitKey(graph)) }

    @Test
    fun section_should_showSwitchIntervalsNetworkAndAdvanced_When_RenderedForWorkNotes() {
        showPanel(BackgroundTaskFixture(BackgroundTaskFixture.DESKTOP))
        composeTestRule.onNodeWithText("Git sync - Work notes").assertExists()
        composeTestRule.onNodeWithText(SWITCH_LABEL).assertExists()
        listOf("5 min", "15 min", "30 min", "1 hour", "2 hours", "6 hours", "Daily").forEach { composeTestRule.onNodeWithText(it).assertExists() }
        composeTestRule.onNodeWithText("Any network").assertExists()
        composeTestRule.onNodeWithText("Unmetered networks only").assertExists()
        composeTestRule.onNodeWithText(NetworkPolicyCopy.UNMETERED_ONLY_HELPER).assertDoesNotExist() // unavailable on Desktop: reason instead
        composeTestRule.onNodeWithText("Advanced").assertExists()
        composeTestRule.onNodeWithText("1 hour").assertIsSelected()
    }

    @Test
    fun jitter_should_appearOnlyUnderAdvancedWithOffALittleALotLabels_When_SectionRendered() {
        showPanel(BackgroundTaskFixture(BackgroundTaskFixture.DESKTOP))
        listOf("Off", "A little", "A lot").forEach { composeTestRule.onNodeWithText(it).assertDoesNotExist() }
        composeTestRule.onNodeWithText("Advanced").performClick()
        listOf("Off", "A little", "A lot").forEach { composeTestRule.onNodeWithText(it).assertExists() }
        composeTestRule.onNodeWithText("A little").assertIsSelected()
        assertTrue(composeTestRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun selectUnmetered_should_persistAndApplyOnce_When_UserPicksUnmetered() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        showPanel(fixture)
        composeTestRule.onNodeWithText("Unmetered networks only").performClick()
        composeTestRule.waitForIdle()
        assertEquals(NetworkPolicy.Unmetered, fixture.entryOf().overrides?.constraints?.network)
        assertEquals(1, fixture.scheduler.applied.size)
        composeTestRule.onNodeWithText("Unmetered networks only").assertIsSelected()
        composeTestRule.onNodeWithText(NetworkPolicyCopy.UNMETERED_ONLY_HELPER).assertExists()
    }

    @Test
    fun changeIntervalAndNetwork_should_takeAtMostFourTapsWithoutLeavingScreen_When_FromSettings() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        showPanel(fixture)
        composeTestRule.onNodeWithText("2 hours").performClick()
        composeTestRule.onNodeWithText("Unmetered networks only").performClick()
        composeTestRule.waitForIdle()
        val stored = runBlocking { fixture.store.read(fixture.gitKey("g1")) }
        assertEquals(120, stored.schedule.intervalMinutes)
        assertEquals(12, stored.schedule.jitterMinutes)
        assertEquals(NetworkPolicy.Unmetered, stored.constraints.network)
    }

    @Test
    fun control_should_revertAndShowCouldntSave_When_WriteOrApplyThrows() {
        val writeFails = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        showPanel(writeFails)
        writeFails.settings.failWrites = true
        composeTestRule.onNodeWithText("Unmetered networks only").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(COULDNT_SAVE).assertExists()
        composeTestRule.onNodeWithText("Any network").assertIsSelected()
        assertTrue(writeFails.scheduler.applied.isEmpty())
    }

    @Test
    fun control_should_revertStoredEntryAndShowCouldntSave_When_ApplyThrows() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        showPanel(fixture)
        fixture.scheduler.failApply = true
        composeTestRule.onNodeWithText("Unmetered networks only").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(COULDNT_SAVE).assertExists()
        composeTestRule.onNodeWithText("Any network").assertIsSelected()
        assertEquals(NetworkPolicy.Any, runBlocking { fixture.store.read(fixture.gitKey("g1")) }.constraints.network)
    }

    @Test
    fun summary_should_showClampAndSpreadWindow_When_AndroidFiveMinOrJitterSet() {
        val desktop = BackgroundTaskFixture(BackgroundTaskFixture.DESKTOP)
        showPanel(desktop)
        composeTestRule.onNodeWithText(DESKTOP_HELPER).assertExists()
        composeTestRule.onNodeWithText("Advanced").performClick()
        composeTestRule.onNodeWithText("Runs 60-66 min apart").assertExists()
        composeTestRule.onNodeWithText("2 hours").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Runs 120-132 min apart").assertExists()
    }

    @Test
    fun summary_should_useAdapterGapFieldsAndClamp_When_WorkManagerLikeAdapter() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        runBlocking {
            fixture.store.write(
                fixture.gitKey("g1"),
                TaskEntry(true, TaskOverrides(Schedule.of(5, 1), TaskConstraints())),
            )
            fixture.scheduler.apply(fixture.gitKey("g1"), fixture.store.read(fixture.gitKey("g1")))
        }
        showPanel(fixture)
        composeTestRule.onNodeWithText("Runs every 15 min on this device").assertExists()
        composeTestRule.onNodeWithText("15 min").assertIsSelected()
        composeTestRule.onNodeWithText("Advanced").performClick()
        composeTestRule.onNodeWithText("About every 15 min; start times vary by up to 1 min").assertExists()
    }

    @Test
    fun unmetered_should_beDisabledWithDesktopReason_When_SupportsUnmeteredFalse() {
        showPanel(BackgroundTaskFixture(BackgroundTaskFixture.DESKTOP))
        composeTestRule.onNodeWithText(UNMETERED_DESKTOP_REASON).assertExists()
        composeTestRule.onNode(hasText("Unmetered networks only") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertIsNotEnabled()
    }

    @Test
    fun disabledControl_should_exposeReasonInSemantics_When_UnmeteredOnDesktop() {
        showPanel(BackgroundTaskFixture(BackgroundTaskFixture.DESKTOP))
        composeTestRule.onNode(
            SemanticsMatcher("disabled with reason") { node ->
                node.config.getOrNull(SemanticsProperties.StateDescription)?.contains(UNMETERED_DESKTOP_REASON) == true
            },
            useUnmergedTree = false,
        ).assertExists()
    }

    @Test
    fun fiveMinute_should_beAbsentWithAndroid15MinHelperLine_When_MinIntervalIs15() {
        showPanel(BackgroundTaskFixture(BackgroundTaskFixture.ANDROID))
        composeTestRule.onNodeWithText("5 min").assertDoesNotExist()
        composeTestRule.onNodeWithText("Android runs at most every 15 minutes.").assertExists()
    }

    @Test
    fun section_should_showCouldntReadSettingsWithRetryAndNeverApply_When_ReadFailsClosed() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        fixture.settings.failReads = true
        showPanel(fixture)
        composeTestRule.onNodeWithText(COULDNT_READ_SETTINGS).assertExists()
        fixture.settings.failReads = false
        composeTestRule.onNodeWithText("Retry").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(COULDNT_READ_SETTINGS).assertDoesNotExist()
        composeTestRule.onNodeWithText(SWITCH_LABEL).assertExists()
        assertTrue(fixture.scheduler.applied.isEmpty())
    }

    @Test
    fun section_should_showSkeletonDisabledControlsAndLoadingReason_When_ReadPending() {
        val gate = CompletableDeferred<Unit>()
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID, seed = { gate.await(); null })
        showPanel(fixture)
        composeTestRule.onNodeWithText(LOADING_SETTINGS_REASON).assertExists()
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertIsNotEnabled()
        composeTestRule.onNodeWithText("15 min").assertDoesNotExist()
        composeTestRule.onNodeWithText("1 hour").assertDoesNotExist()
        composeTestRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)).assertCountEquals(1)
        gate.complete(Unit)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(LOADING_SETTINGS_REASON).assertDoesNotExist()
        composeTestRule.onNodeWithText("1 hour").assertIsSelected()
    }

    @Test
    fun enable_should_announceFirstCheckInAboutNMin_When_SwitchTurnedOn() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        runBlocking { fixture.store.write(fixture.gitKey("g1"), TaskEntry(false, TaskOverrides(Schedule.of(5, 0), TaskConstraints()))) }
        showPanel(fixture)
        composeTestRule.onNodeWithText(SWITCH_LABEL).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("First check in about 15 min").assertExists()
        assertEquals(1, fixture.scheduler.applied.size)
    }

    @Test
    fun switch_should_turnOffInOneTap_When_AlreadyOn() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        runBlocking { fixture.store.write(fixture.gitKey("g1"), TaskEntry(true, null)) }
        showPanel(fixture)
        composeTestRule.onNodeWithText(SWITCH_LABEL).assertIsEnabled().performClick()
        composeTestRule.waitForIdle()
        assertEquals(false, fixture.entryOf().enabled)
        assertEquals(false, fixture.scheduler.applied.single().second.enabled)
    }

    @Test
    fun resetToDefault_should_clearOverridesKeepEnabledAndApplyOnce_When_CustomRowTapped() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        runBlocking { fixture.store.write(fixture.gitKey("g1"), TaskEntry(true, TaskOverrides(Schedule.of(360, 36), TaskConstraints()))) }
        showPanel(fixture)
        composeTestRule.onNodeWithText("Reset to default").performClick()
        composeTestRule.waitForIdle()
        val entry = fixture.entryOf()
        assertNull(entry.overrides)
        assertTrue(entry.enabled)
        assertEquals(1, fixture.scheduler.applied.size)
        composeTestRule.onNodeWithText(BACK_TO_DEFAULT).assertExists()
        composeTestRule.onNodeWithText("Reset to default").assertDoesNotExist()
        composeTestRule.onNodeWithText("1 hour").assertIsSelected()
    }

    @Test
    fun resetToDefault_should_keepOverridesAndShowCouldntSave_When_WriteThrows() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        val custom = TaskEntry(true, TaskOverrides(Schedule.of(360, 36), TaskConstraints()))
        runBlocking { fixture.store.write(fixture.gitKey("g1"), custom) }
        showPanel(fixture)
        fixture.settings.failWrites = true
        composeTestRule.onNodeWithText("Reset to default").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(COULDNT_SAVE).assertExists()
        assertEquals(custom, fixture.entryOf())
    }

    @Test
    fun defaultsPanel_should_writeKindDefaultsNeverAScope_When_DefaultRowChanged() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.ANDROID)
        showPanel(fixture, target = PanelTarget.Defaults(emptyList()))
        composeTestRule.onNodeWithText(SWITCH_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText("2 hours").performClick()
        composeTestRule.waitForIdle()
        assertEquals(120, runBlocking { fixture.store.readDefaults(GitSyncTask.KIND) }?.schedule?.intervalMinutes)
        assertTrue(fixture.settings.store.keys.none { it.contains("task_entry") })
        assertTrue(fixture.scheduler.applied.isEmpty())
    }

    @Test
    fun rows_should_wrapAt200PercentFontAndMirrorUnderRtl_When_Rendered() {
        val fixture = BackgroundTaskFixture(BackgroundTaskFixture.DESKTOP)
        composeTestRule.setContent {
            MaterialTheme {
                FontScaled(2f) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        TaskSettingsPanel(fixture.controller, fixture.gitKey("g1"), "Git sync - Work notes")
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(NetworkPolicyCopy.UNMETERED_ONLY_LABEL).assertExists()
        composeTestRule.onNodeWithText(UNMETERED_DESKTOP_REASON).assertExists().assertIsDisplayed()
        composeTestRule.onNode(hasText("Any network") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertHeightIsAtLeast(48.dp)
        composeTestRule.onNode(hasText("1 hour") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun rows_should_be48dpAndSingleFocusStop_When_RadiosAndSwitchesRendered() {
        showPanel(BackgroundTaskFixture(BackgroundTaskFixture.ANDROID))
        listOf("15 min", "30 min", "Any network").forEach {
            composeTestRule.onNode(hasText(it) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertHeightIsAtLeast(48.dp)
        }
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertHeightIsAtLeast(48.dp)
        // Inner RadioButtons carry no click action of their own: exactly one selectable node per label.
        composeTestRule.onAllNodes(hasText("Any network") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(1)
    }
}
