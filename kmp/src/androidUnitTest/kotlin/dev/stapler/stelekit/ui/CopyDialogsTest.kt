// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.merge.ClosureSummary
import dev.stapler.stelekit.merge.CopyDirection
import dev.stapler.stelekit.merge.DryRunSummary
import dev.stapler.stelekit.merge.MergePhase
import dev.stapler.stelekit.merge.MergeProgress
import dev.stapler.stelekit.merge.MergeResult
import dev.stapler.stelekit.merge.PageFailure
import dev.stapler.stelekit.ui.screens.copy.CopyPullSwitchConfirmDialog
import dev.stapler.stelekit.ui.screens.copy.CopyProgressDialog
import dev.stapler.stelekit.ui.screens.copy.CopyResultDialog
import dev.stapler.stelekit.ui.screens.copy.DryRunDialog
import dev.stapler.stelekit.ui.screens.copy.DryRunUiState
import dev.stapler.stelekit.ui.screens.copy.InterruptedCopyDialog
import dev.stapler.stelekit.ui.screens.copy.InterruptedCopyNotice
import dev.stapler.stelekit.ui.screens.copy.InterruptedProblem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Wording, gating and actions for the cross-graph copy dialogs (design/ux.md S4-S6, S9). */
@RunWith(RobolectricTestRunner::class)
class CopyDialogsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun dryRun(
        state: DryRunUiState,
        onConfirm: () -> Unit = {},
        onBack: () -> Unit = {},
        direction: CopyDirection = CopyDirection.Push,
    ) = rule.setContent {
        DryRunDialog(direction, "Personal", "Work graph", state, onConfirm = onConfirm, onBack = onBack)
    }

    private fun result(
        new: Int = 3, combined: Int = 5, unchanged: Int = 20, conflicts: Int = 2,
        failed: List<PageFailure> = listOf(PageFailure(0, "Budget 2026", DomainError.DatabaseError.WriteFailed("file write error"))),
        total: Int = 28, stoppedAfter: Int? = null,
    ) = MergeResult("m1", total, new, combined, unchanged, failed, conflicts, 0, stoppedAfter)

    @Test
    fun dryRun_wording_and_button_count() {
        var confirmed = 0
        dryRun(DryRunUiState.Ready(DryRunSummary(new = 3, combined = 5, unchanged = 20)), onConfirm = { confirmed++ })
        rule.onNodeWithText("3 new - will be created").assertExists()
        rule.onNodeWithText("5 already exist - blocks will be combined (nothing removed)").assertExists()
        rule.onNodeWithText("20 unchanged - nothing to do").assertExists()
        rule.onNodeWithText("Nothing in the destination is deleted or overwritten. The source graph is not changed.").assertExists()
        rule.onNodeWithText("Copy 8 pages to \"Work graph\"?").assertExists()
        rule.onNodeWithText("Copy 8 pages").assertIsEnabled().performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun dryRun_nothing_to_copy_disables_commit() {
        dryRun(DryRunUiState.Ready(DryRunSummary(unchanged = 4)))
        rule.onNodeWithText("Nothing to copy - destination already has all selected content").assertExists()
        rule.onNodeWithText("Copy 0 pages").assertIsNotEnabled()
    }

    @Test
    fun dryRun_checking_is_determinate_and_back_cancels_plan() {
        var backs = 0
        dryRun(DryRunUiState.Checking(40, 100), onBack = { backs++ })
        rule.onNodeWithText("Checking 40 of 100 pages...").assertExists()
        rule.onNodeWithText("Copy pages").assertIsNotEnabled()
        rule.onNodeWithText("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun dryRun_stale_banner_and_recomputed_count() {
        dryRun(DryRunUiState.Ready(DryRunSummary(new = 1, combined = 1), stale = true))
        rule.onNodeWithText("Things changed - review again").assertExists()
        rule.onNodeWithText("Copy 2 pages").assertIsEnabled()
    }

    @Test
    fun dryRun_large_closure_needs_second_press_and_assets_renamed_shown() {
        var confirmed = 0
        dryRun(
            DryRunUiState.Ready(DryRunSummary(new = 5, assetsRenamed = 2), closure = ClosureSummary(added = 250, requiresConfirmation = true)),
            onConfirm = { confirmed++ },
        )
        rule.onNodeWithText("2 assets renamed to avoid overwriting").assertExists()
        rule.onNodeWithText("Copy 5 pages").performClick()
        assertEquals(0, confirmed)
        rule.onNodeWithText("Yes, Copy 5 pages").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun dryRun_pull_title_names_both_graphs() {
        dryRun(DryRunUiState.Ready(DryRunSummary(new = 2)), direction = CopyDirection.Pull)
        rule.onNodeWithText("Copy 2 pages from \"Personal\" into \"Work graph\"?").assertExists()
    }

    @Test
    fun progress_has_stop_not_cancel_and_range_info() {
        var stops = 0
        rule.setContent {
            CopyProgressDialog(
                CopyDirection.Push, "Personal", "Work graph",
                MergeProgress(MergePhase.Applying, 1200, 4000), "Roadmap", onStop = { stops++ },
            )
        }
        rule.onNodeWithText("Stop").assertIsEnabled().performClick()
        assertEquals(1, stops)
        rule.onNodeWithText("Pages already copied stay copied.").assertExists()
        rule.onNodeWithText("1,200 of 4,000 pages").assertExists()
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(1)
        rule.onAllNodes(hasText("Cancel", substring = true, ignoreCase = true)).assertCountEquals(0)
    }

    @Test
    fun progress_background_notice_for_push_graph_switch() {
        rule.setContent {
            CopyProgressDialog(
                CopyDirection.Push, "Personal", "Work graph",
                MergeProgress(MergePhase.Applying, 1, 10), null, onStop = {}, backgroundNotice = true,
            )
        }
        rule.onNodeWithText("Copy to Work graph continues in the background").assertExists()
    }

    @Test
    fun pull_switch_confirm_buttons() {
        var stopped = 0
        var kept = 0
        rule.setContent { CopyPullSwitchConfirmDialog("Personal", { stopped++ }, { kept++ }) }
        rule.onNodeWithText("A copy into Personal is running. Stop it and switch?").assertExists()
        rule.onNodeWithText("Keep copying").performClick()
        rule.onNodeWithText("Stop and switch").performClick()
        assertEquals(1, kept)
        assertEquals(1, stopped)
    }

    @Test
    fun result_categories_and_actions() {
        val calls = mutableListOf<String>()
        rule.setContent {
            CopyResultDialog(
                CopyDirection.Push, "Personal", "Work graph", result(),
                onDone = { calls += "done" }, onRetryFailures = { calls += "retry" },
                onReviewConflicts = { calls += "review" }, onUndo = { calls += "undo" },
            )
        }
        rule.onNodeWithText("Copied to \"Work graph\"").assertExists()
        listOf("3 new", "5 combined", "20 unchanged", "2 conflicts kept (both versions)", "1 failed").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        rule.onNodeWithText("Failed pages (1)").performScrollTo().performClick()
        rule.onNodeWithText("Budget 2026 - file write error").assertExists()
        rule.onNodeWithText("Retry failed").performScrollTo().performClick()
        rule.onNodeWithText("Review 2 conflicts").performScrollTo().performClick()
        rule.onNodeWithText("Undo this copy").performScrollTo().performClick()
        rule.onNodeWithText("Done").performScrollTo().performClick()
        assertEquals(listOf("retry", "review", "undo", "done"), calls)
    }

    @Test
    fun result_pull_title_names_source() {
        rule.setContent {
            CopyResultDialog(CopyDirection.Pull, "Personal", "Work graph", result(failed = emptyList(), conflicts = 0), onDone = {})
        }
        rule.onNodeWithText("Copied pages from \"Personal\"").assertExists()
        rule.onNodeWithText("Retry failed").assertDoesNotExist()
    }

    @Test
    fun result_stopped_title_body_and_actions() {
        val calls = mutableListOf<String>()
        rule.setContent {
            CopyResultDialog(
                CopyDirection.Push, "Personal", "Work graph",
                result(new = 1000, combined = 100, unchanged = 100, conflicts = 0, failed = emptyList(), total = 4000, stoppedAfter = 1200),
                onDone = { calls += "done" }, onUndo = { calls += "undo" }, onContinue = { calls += "continue" },
            )
        }
        rule.onNodeWithText("Stopped after 1,200 of 4,000").assertExists()
        rule.onNodeWithText("1,200 pages were copied and kept. 2,800 were not copied.").assertExists()
        rule.onNodeWithText("Continue").performScrollTo().performClick()
        rule.onNodeWithText("Undo this copy").performScrollTo().performClick()
        rule.onNodeWithText("Done").performScrollTo().performClick()
        assertEquals(listOf("continue", "undo", "done"), calls)
        rule.onAllNodes(hasText("Cancel", substring = true, ignoreCase = true)).assertCountEquals(0)
    }

    @Test
    fun result_rerun_hides_undo() {
        rule.setContent {
            CopyResultDialog(
                CopyDirection.Push, "Personal", "Work graph",
                result(new = 0, combined = 0, unchanged = 20, conflicts = 0, failed = emptyList(), total = 20), onDone = {},
            )
        }
        rule.onNodeWithText("Nothing needed copying.").assertExists()
        rule.onNodeWithText("Undo this copy").assertDoesNotExist()
    }

    @Test
    fun interrupted_notice_resume_and_problem_states() {
        var resumed = 0
        rule.setContent {
            InterruptedCopyDialog(InterruptedCopyNotice("Work graph", 1200, 4000), onResume = { resumed++ }, onDismiss = {})
        }
        rule.onNodeWithText("A copy to \"Work graph\" was interrupted").assertExists()
        rule.onNodeWithText("1,200 of 4,000 pages were copied.").assertExists()
        rule.onNodeWithText("Resume").performClick()
        assertEquals(1, resumed)
    }

    @Test
    fun interrupted_notice_cannot_resume_has_no_resume_button() {
        rule.setContent {
            InterruptedCopyDialog(
                InterruptedCopyNotice("Work graph", 3, problem = InterruptedProblem.StagingGone), onResume = {}, onDismiss = {},
            )
        }
        rule.onNodeWithText("Resume").assertDoesNotExist()
        rule.onNodeWithText("Open picker").assertExists()
    }

    @Test
    fun large_font_and_rtl_keep_actions_reachable() {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(d.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                CopyResultDialog(CopyDirection.Push, "Personal", "Work graph", result(), onDone = {})
            }
        }
        rule.onNodeWithText("Done").assertExists().assertIsEnabled()
        rule.onNodeWithText("2 conflicts kept (both versions)").assertExists()
    }
}
