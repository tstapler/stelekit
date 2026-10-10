// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.merge.DryRunSummary
import dev.stapler.stelekit.merge.MergePhase
import dev.stapler.stelekit.merge.MergeProgress
import dev.stapler.stelekit.merge.MergeResult
import dev.stapler.stelekit.merge.PageSelection
import dev.stapler.stelekit.merge.PlanRequest
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.ui.screens.copy.CopyFlowActions
import dev.stapler.stelekit.ui.screens.copy.CopyFlowContent
import dev.stapler.stelekit.ui.screens.copy.CopyFlowState
import dev.stapler.stelekit.ui.screens.copy.CopyPagesEvent
import dev.stapler.stelekit.ui.screens.copy.CopyStage
import dev.stapler.stelekit.ui.screens.copy.DryRunUiState
import dev.stapler.stelekit.ui.screens.copy.DryRunView
import dev.stapler.stelekit.ui.screens.copy.InterruptedCopyNotice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Which dialog each flow stage shows and that its buttons reach the matching action. */
@RunWith(RobolectricTestRunner::class)
class CopyFlowHostTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private val actions = object : CopyFlowActions {
        override fun onPickerEvent(event: CopyPagesEvent) { calls += "picker" }
        override fun closeFlow() { calls += "closeFlow" }
        override fun dryRunBack() { calls += "dryRunBack" }
        override fun dryRunRetry() { calls += "dryRunRetry" }
        override fun dryRunChooseAnother() { calls += "dryRunChooseAnother" }
        override fun dryRunConfirm() { calls += "dryRunConfirm" }
        override fun stop() { calls += "stop" }
        override fun done() { calls += "done" }
        override fun retryFailedPages() { calls += "retryFailedPages" }
        override fun continueStopped() { calls += "continueStopped" }
        override fun runFailedRetry() { calls += "runFailedRetry" }
        override fun runFailedChooseAnother() { calls += "runFailedChooseAnother" }
        override fun requestUndo() { calls += "requestUndo" }
        override fun cancelUndo() { calls += "cancelUndo" }
        override fun confirmUndo() { calls += "confirmUndo" }
        override fun reviewConflicts() { calls += "reviewConflicts" }
        override fun closeConflicts() { calls += "closeConflicts" }
        override fun dismissInterrupted() { calls += "dismissInterrupted" }
        override fun openPickerFromInterrupted() { calls += "openPickerFromInterrupted" }
        override fun resume() { calls += "resume" }
    }

    private val request = PlanRequest(PageSelection(), GraphId("src"), GraphId("dst"), "Personal")

    private fun show(state: CopyFlowState, progress: MergeProgress = MergeProgress()) = rule.setContent {
        MaterialTheme {
            CopyFlowContent(state, progress, nameOf = { if (it.value == "dst") "Work graph" else it.value }, actions = actions)
        }
    }

    @Test
    fun finished_result_offers_conflicts_undo_and_done() {
        val result = MergeResult("m1", 10, 3, 5, 2, emptyList(), 2, 0)
        show(CopyFlowState(stage = CopyStage.Finished, request = request, result = result))

        rule.onNodeWithText("Copied to \"Work graph\"").assertExists()
        rule.onNodeWithText("Review 2 conflicts").performClick()
        rule.onNodeWithText("Undo this copy").performClick()
        rule.onNodeWithText("Done").performClick()
        assertEquals(listOf("reviewConflicts", "requestUndo", "done"), calls)
    }

    @Test
    fun running_in_the_foreground_shows_progress_with_stop() {
        show(
            CopyFlowState(stage = CopyStage.Running, request = request),
            MergeProgress(MergePhase.Applying, done = 40, total = 100),
        )
        rule.onNodeWithText("Copying to \"Work graph\"").assertExists()
        rule.onNodeWithText("Stop").performClick()
        assertEquals(listOf("stop"), calls)
    }

    @Test
    fun running_after_a_graph_switch_shows_the_background_notice_instead_of_the_dialog() {
        show(CopyFlowState(stage = CopyStage.Running, request = request, backgrounded = true))
        rule.onNodeWithText("Copy to Work graph continues in the background").assertExists()
        rule.onNodeWithText("Copying to \"Work graph\"").assertDoesNotExist()
    }

    @Test
    fun stale_dry_run_shows_the_banner_and_confirm_reaches_the_controller() {
        val ready = DryRunUiState.Ready(DryRunSummary(new = 1, combined = 1), stale = true)
        show(CopyFlowState(stage = CopyStage.Idle, request = request, dryRun = DryRunView(ready)))
        rule.onNodeWithText("Things changed - review again").assertExists()
        rule.onNodeWithText("Copy 2 pages").performClick()
        assertEquals(listOf("dryRunConfirm"), calls)
    }

    @Test
    fun undo_confirmation_names_the_destination_and_has_cancel() {
        show(CopyFlowState(stage = CopyStage.ConfirmUndo, request = request))
        rule.onNodeWithText("Undo this copy?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancelUndo"), calls)
    }

    @Test
    fun interrupted_notice_resume() {
        show(CopyFlowState(stage = CopyStage.Interrupted, interrupted = InterruptedCopyNotice("Work graph", 12, 40)))
        rule.onNodeWithText("A copy to \"Work graph\" was interrupted").assertExists()
        rule.onNodeWithText("Resume").performClick()
        assertEquals(listOf("resume"), calls)
    }

    @Test
    fun run_failure_offers_choose_another() {
        show(CopyFlowState(stage = CopyStage.RunFailed, request = request, failure = "disk full"))
        rule.onNodeWithText("Couldn't copy to Work graph").assertExists()
        rule.onNodeWithText("disk full").assertExists()
        rule.onNodeWithText("Choose another destination").performClick()
        assertEquals(listOf("runFailedChooseAnother"), calls)
    }
}
