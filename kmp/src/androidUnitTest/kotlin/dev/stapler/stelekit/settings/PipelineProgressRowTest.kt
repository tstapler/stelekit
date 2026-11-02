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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

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

    private fun show(progress: PipelineProgress, stoppedByUser: Boolean = false) {
        composeRule.setContent {
            PipelineProgressRow(
                progress, stoppedByUser,
                onPause = { pauses++ }, onResume = { resumes++ }, onCancel = { cancels++ },
                onStartAgain = { startAgains++ }, onViewLog = { viewLogs++ },
            )
        }
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
            PipelineProgressRow(
                progress, stopped,
                onPause = {}, onResume = { resumes++ }, onCancel = {},
                onStartAgain = { startAgains++ }, onViewLog = {},
            )
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
            PipelineProgressRow(progress, false, {}, { resumes++ }, {}, {}, {})
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
}
