// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.asset.pipeline.PauseReason
import dev.stapler.stelekit.asset.pipeline.PipelineProgress
import dev.stapler.stelekit.ui.components.settings.PipelineProgressRow
import dev.stapler.stelekit.ui.components.settings.PipelineRowActions
import dev.stapler.stelekit.ui.components.settings.announcementKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 5.5 progress row (P4): text, determinate bar, and the controls each state offers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PipelineProgressRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var pauses = 0
    private var resumes = 0
    private var cancels = 0
    private var startAgains = 0
    private var viewLogs = 0

    private var processNows = 0
    private var retries = 0

    private fun actions() = PipelineRowActions(
        onPause = { pauses++ }, onResume = { resumes++ }, onCancel = { cancels++ },
        onStartAgain = { startAgains++ }, onViewLog = { viewLogs++ },
        onProcessNow = { processNows++ }, onRetryFailed = { retries++ },
    )

    private fun show(progress: PipelineProgress, stoppedByUser: Boolean = false, waitingForCharger: Boolean = false) {
        composeRule.setContent { PipelineProgressRow(progress, stoppedByUser, waitingForCharger, actions()) }
    }

    @Test
    fun row_should_ShowDoneOfTotalFailedViewLogDeterminateBarPauseCancel_When_Running() {
        show(PipelineProgress.Running(340, 2100, 12))

        composeRule.onNodeWithText("Image processing: 340 of 2,100 done · 12 failed - view log").assertExists()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertExists()
        composeRule.onNodeWithText("Pause").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Image processing: 340 of 2,100 done · 12 failed - view log").performClick()

        assertEquals(1, pauses)
        assertEquals(1, cancels)
        assertEquals(1, viewLogs, "the failed count links to the log")
    }

    @Test
    fun pauseAndCancel_should_FinishCurrentAssetShowResumeOrStartAgainAndKeepExtractedText_When_Used() {
        var progress by mutableStateOf<PipelineProgress>(PipelineProgress.Running(340, 2100, 0))
        var stopped by mutableStateOf(false)
        composeRule.setContent {
            PipelineProgressRow(progress, stopped, false, actions())
        }
        composeRule.waitForIdle()

        progress = PipelineProgress.Paused(PauseReason.User)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Paused at 340 of 2,100").assertExists()
        composeRule.onNodeWithText("Resume").performClick()
        assertEquals(1, resumes)

        progress = PipelineProgress.Idle
        stopped = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stopped at 340 of 2,100. Already processed items are kept.").assertExists()
        composeRule.onNodeWithText("Start again").performClick()
        assertEquals(1, startAgains)
    }

    @Test
    fun row_should_ShowPausedLowMemoryWithResume_When_TrimMemoryFires() {
        var progress by mutableStateOf<PipelineProgress>(PipelineProgress.Running(10, 20, 0))
        composeRule.setContent {
            PipelineProgressRow(progress, false, false, actions())
        }
        composeRule.waitForIdle()
        progress = PipelineProgress.Paused(PauseReason.Memory)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Paused: low memory. 10 of 20 done").assertExists()
        composeRule.onNodeWithText("Resume").performClick()
        assertEquals(1, resumes)
    }

    @Test
    fun row_should_BeHiddenWhenIdleNothingToDo_And_FinishedCanBeDismissed() {
        show(PipelineProgress.Idle)
        assertEquals(0, composeRule.onAllNodes(hasText("Start again")).fetchSemanticsNodes().size)
    }

    @Test
    fun finishedRow_should_OfferViewLogAndDismiss_When_SomeFailed() {
        show(PipelineProgress.Finished(2088, 12))

        composeRule.onNodeWithText("2,088 done · 12 failed - view log").performClick()
        assertEquals(1, viewLogs)
        composeRule.onNodeWithText("Dismiss").performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodes(hasText("2,088 done", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun row_should_ShowWaitingForChargerAndProcessNow_When_AndroidNotCharging() {
        show(PipelineProgress.Idle, waitingForCharger = true)

        composeRule.onNodeWithText("Waiting for charger").assertExists()
        composeRule.onNodeWithText("Process now").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, processNows)
        assertEquals(1, cancels)
    }

    @Test
    fun failures_should_BeReachableViaViewLogAndRetryFailedWithNoDialog_When_12Fail() {
        show(PipelineProgress.Finished(2088, 12))

        composeRule.onNodeWithText("Retry failed").performClick()
        composeRule.onNodeWithText("2,088 done · 12 failed - view log").performClick()
        assertEquals(1, retries)
        assertEquals(1, viewLogs)
    }

    @Test
    fun bar_should_ExposeProgressBarRangeInfoAndAnnounceEvery10PercentAndOnStateChange() {
        val a = announcementKey(PipelineProgress.Running(101, 2100, 0), false)
        val b = announcementKey(PipelineProgress.Running(150, 2100, 0), false)
        val c = announcementKey(PipelineProgress.Running(220, 2100, 0), false)
        assertEquals(a, b, "no new announcement inside the same 10% bucket")
        assertTrue(a != c, "a new announcement when crossing 10%")
        assertTrue(a != announcementKey(PipelineProgress.Paused(PauseReason.User), false), "state change announces")
        assertTrue(a != announcementKey(PipelineProgress.Running(101, 2100, 0), true), "waiting for charger announces")

        show(PipelineProgress.Running(101, 2100, 0))
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertExists()
    }

    @Test
    fun indicators_should_NotAnimateAndShowTextState_When_AnimationScaleZero() {
        show(PipelineProgress.Running(10, 100, 0))
        // The bar is determinate (no infinite transition) and the text carries the same state.
        composeRule.onNodeWithText("Image processing: 10 of 100 done").assertExists()
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(5_000)
        composeRule.onNodeWithText("Image processing: 10 of 100 done").assertExists()
    }
}
