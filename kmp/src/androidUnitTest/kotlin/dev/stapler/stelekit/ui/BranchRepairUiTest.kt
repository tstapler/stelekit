// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.BranchRepairResult
import dev.stapler.stelekit.git.FirstSyncPreview
import dev.stapler.stelekit.git.PreviewCommit
import dev.stapler.stelekit.git.PreviewRemoteBranch
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.ui.components.GitSyncStatus
import dev.stapler.stelekit.ui.components.SyncStatusBadge
import dev.stapler.stelekit.ui.screens.git.BranchRepairSheet
import dev.stapler.stelekit.ui.screens.git.FirstSyncPreviewState
import dev.stapler.stelekit.ui.screens.git.FirstSyncReviewDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class BranchRepairUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun missing(vararg available: String) =
        DomainError.GitError.RemoteBranchNotFound("origin", "main", available.toList())

    @Test
    fun badge_showsBranchNotFoundCopy_andTapOpensRepairInsteadOfRetrying() {
        var repairs = 0
        var syncs = 0
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusBadge(
                    status = GitSyncStatus(SyncState.Error(missing("master"))),
                    onSyncClick = { syncs++ },
                    onBranchRepair = { repairs++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Branch 'main' not found on remote — tap to fix").assertExists()
        composeTestRule.onNodeWithContentDescription("Sync error — tap to fix").performClick()

        assertEquals(1, repairs)
        assertEquals(0, syncs)
    }

    @Test
    fun badge_showsReviewFirstSync_whenARepairIsPending_andIdle() {
        var reviews = 0
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusBadge(
                    status = GitSyncStatus(SyncState.Idle, firstSyncReviewPending = true),
                    onSyncClick = {},
                    onReviewFirstSync = { reviews++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Review first sync").performClick()

        assertEquals(1, reviews)
    }

    @Test
    fun sheet_withOneRemoteBranch_offersUseThatBranch_andNeverSyncs() {
        var used: String? = null
        composeTestRule.setContent {
            MaterialTheme {
                BranchRepairSheet(
                    error = missing("master"),
                    busy = false, saving = false, failure = null,
                    onUse = { used = it }, onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Branch 'main' not found on remote").assertExists()
        composeTestRule.onNodeWithText("Use 'master'").performClick()

        assertEquals("master", used)
    }

    @Test
    fun sheet_withSeveralRemoteBranches_requiresAnExplicitChoice() {
        var used: String? = null
        composeTestRule.setContent {
            MaterialTheme {
                BranchRepairSheet(
                    error = missing("dev", "master"),
                    busy = false, saving = false, failure = null,
                    onUse = { used = it }, onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Use selected branch").assertIsNotEnabled()
        composeTestRule.onNodeWithText("master").performClick()
        composeTestRule.onNodeWithText("Use selected branch").assertIsEnabled().performClick()

        assertEquals("master", used)
    }

    @Test
    fun sheet_disablesApply_whileASyncIsRunning_andShowsFailureLine() {
        composeTestRule.setContent {
            MaterialTheme {
                BranchRepairSheet(
                    error = missing("master"),
                    busy = true, saving = false, failure = BranchRepairResult.Stale("trunk"),
                    onUse = {}, onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Use 'master'").assertIsNotEnabled()
        composeTestRule.onNodeWithText("A sync is running. Wait for it to finish.").assertExists()
        composeTestRule.onNodeWithText("This changed while the sheet was open. Review the new details.").assertExists()
    }

    @Test
    fun review_syncNowIsTheOnlySyncTrigger_andChangeBackIsOfferedWhenKnown() {
        var syncs = 0
        composeTestRule.setContent {
            MaterialTheme {
                FirstSyncReviewDialog(
                    remoteBranch = "origin/master", previousBranch = "main", syncing = false, resultLine = null,
                    onSyncNow = { syncs++ }, onChangeBack = {}, onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Branch set to origin/master. Review before syncing.").assertExists()
        composeTestRule.onNodeWithText("Change back to 'main'").assertExists()
        assertEquals(0, syncs)
        composeTestRule.onNodeWithText("Sync now").performClick()
        assertEquals(1, syncs)
    }

    @Test
    fun review_showsReadOnlyPreview_andRetryAfterFailure() {
        var retries = 0
        val ready = FirstSyncPreview(
            syncRef = "origin/master", ahead = 2, behind = 1,
            remoteBranches = listOf(PreviewRemoteBranch("master", true), PreviewRemoteBranch("main", false)),
            remoteBranchesLive = true,
            localOnlyCommits = listOf(PreviewCommit("abc1234", "edit journal", 0L)),
            filesToDelete = 3,
        )
        var state by androidx.compose.runtime.mutableStateOf<FirstSyncPreviewState>(FirstSyncPreviewState.Failed("timed out"))
        composeTestRule.setContent {
            MaterialTheme {
                FirstSyncReviewDialog(
                    remoteBranch = "origin/master", previousBranch = null, syncing = false, resultLine = null,
                    onSyncNow = {}, onChangeBack = {}, onDismiss = {},
                    preview = state, onRetryPreview = { retries++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Preview unavailable: timed out").assertExists()
        composeTestRule.onNodeWithText("Retry preview").performClick()
        assertEquals(1, retries)

        state = FirstSyncPreviewState.Ready(ready)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("2 ahead, 1 behind origin/master").assertExists()
        composeTestRule.onNodeWithText("Remote branches: master, main (not the sync branch)").assertExists()
        composeTestRule.onNodeWithText("3 files would be deleted from the remote").assertExists()
        composeTestRule.onNodeWithText("abc1234 edit journal").assertExists()
    }
}
