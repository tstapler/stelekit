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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import dev.stapler.stelekit.ui.screens.git.MassChangeConfirmDialog
import dev.stapler.stelekit.ui.screens.git.RepairNeededDialog
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

        composeTestRule.onNodeWithText("Preview unavailable: timed out. Nothing was changed.").assertExists()
        composeTestRule.onNodeWithText("Retry preview").performClick()
        assertEquals(1, retries)

        state = FirstSyncPreviewState.Ready(ready)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("2 ahead, 1 behind origin/master").assertExists()
        composeTestRule.onNodeWithText("Remote branches: master, main (not the sync branch)").assertExists()
        composeTestRule.onNodeWithText("3 files would be deleted from the remote").assertExists()
        composeTestRule.onNodeWithText("abc1234 edit journal").assertExists()
    }

    // --- Task 2.1f / 2.2d0 / 2.3f follow-ups: repair routing, Sync without preview, typed-count confirm ---

    private fun reviewDialog(
        preview: FirstSyncPreviewState?,
        syncing: Boolean = false,
        onSyncNow: () -> Unit = {},
        onCancelPreview: () -> Unit = {},
        onSyncWithoutPreview: () -> Unit = {},
        onOpenDetails: (() -> Unit)? = null,
    ) = composeTestRule.setContent {
        MaterialTheme {
            FirstSyncReviewDialog(
                remoteBranch = "origin/master", previousBranch = null, syncing = syncing, resultLine = null,
                onSyncNow = onSyncNow, onChangeBack = {}, onDismiss = {},
                preview = preview, onCancelPreview = onCancelPreview,
                onSyncWithoutPreview = onSyncWithoutPreview, onOpenDetails = onOpenDetails,
            )
        }
    }

    @Test
    fun badge_routesRepairNeededAndMassChangeToDetails_insteadOfRetrying() {
        var details = 0
        var syncs = 0
        var state by androidx.compose.runtime.mutableStateOf<SyncState>(SyncState.Error(DomainError.GitError.RepairNeeded("MERGING")))
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusBadge(status = GitSyncStatus(state), onSyncClick = { syncs++ }, onSyncDetails = { details++ })
            }
        }

        composeTestRule.onNodeWithContentDescription("Sync error — tap for details").performClick()
        state = SyncState.Error(DomainError.GitError.MassChangeBlocked(30, 20, listOf("pages/a.md")))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Sync error — tap for details").performClick()

        assertEquals(2, details)
        assertEquals(0, syncs)
    }

    @Test
    fun repairDialog_offersAbort_andDisablesItWhileASyncRuns() {
        var aborts = 0
        var busy by androidx.compose.runtime.mutableStateOf(true)
        composeTestRule.setContent {
            MaterialTheme {
                RepairNeededDialog(DomainError.GitError.RepairNeeded("MERGING"), busy = busy, onAbortMerge = { aborts++ }, onClose = {})
            }
        }

        composeTestRule.onNodeWithText("Repository needs repair").assertExists()
        composeTestRule.onNodeWithText("Abort merge").assertIsNotEnabled()
        composeTestRule.onNodeWithText("A sync is running. Wait for it to finish.").assertExists()
        busy = false
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Abort merge").assertIsEnabled().performClick()

        assertEquals(1, aborts)
    }

    private fun massChange(journals: Int) = DomainError.GitError.MassChangeBlocked(
        deleted = 214, threshold = 20,
        sample = List(journals) { "journals/2026_10_0$it.md" } + List(50 - journals) { "pages/p$it.md" },
    )

    @Test
    fun massChangeDialog_dropsNonDigits_andConfirmsOnTheDerivedCount() {
        var confirms = 0
        composeTestRule.setContent {
            MaterialTheme {
                MassChangeConfirmDialog(massChange(journals = 5), busy = false, onConfirm = { confirms++ }, onCancel = {})
            }
        }

        composeTestRule.onNodeWithText("Sync paused: 214 files would be deleted").assertExists()
        composeTestRule.onNodeWithText("Sync and delete").assertIsNotEnabled()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("5 files")
        composeTestRule.onNodeWithText("Sync and delete").assertIsEnabled().performClick()

        assertEquals(1, confirms)
    }

    @Test
    fun massChangeDialog_showsMismatchHintWithoutTheAnswer_andStaysDisabled() {
        composeTestRule.setContent {
            MaterialTheme {
                MassChangeConfirmDialog(massChange(journals = 5), busy = false, onConfirm = {}, onCancel = {})
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextInput("7")
        composeTestRule.onNodeWithText("Doesn't match — count the list again").assertExists()
        composeTestRule.onNodeWithText("Sync and delete").assertIsNotEnabled()
    }

    @Test
    fun massChangeDialog_staysBlocked_whenNoCountCanBeDerived() {
        composeTestRule.setContent {
            MaterialTheme {
                MassChangeConfirmDialog(
                    DomainError.GitError.MassChangeBlocked(0, 20, listOf("pages/a.md")),
                    busy = false, onConfirm = {}, onCancel = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Can't confirm here. Run git status in the dev console to review.").assertExists()
        composeTestRule.onNodeWithText("Sync and delete").assertIsNotEnabled()
    }

    @Test
    fun review_whileLoading_showsCheckingWithCancel_andDisablesSyncNow() {
        var cancels = 0
        reviewDialog(FirstSyncPreviewState.Loading, onCancelPreview = { cancels++ })

        composeTestRule.onNodeWithText("Checking the remote…").assertExists()
        composeTestRule.onNodeWithText("Sync now").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Sync without preview").assertDoesNotExist()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(1, cancels)
    }

    @Test
    fun review_syncWithoutPreview_isOfferedOnlyAfterAFailure_andNotWhenLoaded() {
        reviewDialog(FirstSyncPreviewState.Failed("timed out"))
        composeTestRule.onNodeWithText("Sync without preview").assertExists()
        composeTestRule.onNodeWithText("Sync now").assertIsNotEnabled()
    }

    @Test
    fun review_syncWithoutPreview_isAbsentOnceTheNumbersLoaded() {
        val ready = FirstSyncPreview("origin/master", 0, 0, emptyList(), true, emptyList(), 0)
        reviewDialog(FirstSyncPreviewState.Ready(ready))
        composeTestRule.onNodeWithText("Sync without preview").assertDoesNotExist()
        composeTestRule.onNodeWithText("Sync now").assertIsEnabled()
    }

    @Test
    fun review_syncWithoutPreview_needsTheTypedWord_andCancelChangesNothing() {
        var runs = 0
        reviewDialog(FirstSyncPreviewState.Failed("timed out"), onSyncWithoutPreview = { runs++ })

        composeTestRule.onNodeWithText("Sync without preview").performClick()
        composeTestRule.onNodeWithText("Sync without a preview?").assertExists()
        composeTestRule.onAllNodesWithText("Sync without preview")[1].assertIsNotEnabled()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("syn")
        composeTestRule.onAllNodesWithText("Sync without preview")[1].assertIsNotEnabled()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Sync without a preview?").assertDoesNotExist()
        assertEquals(0, runs)

        composeTestRule.onNodeWithText("Sync without preview").performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("  SYNC ")
        composeTestRule.onAllNodesWithText("Sync without preview")[1].assertIsEnabled().performClick()

        assertEquals(1, runs)
        composeTestRule.onNodeWithText("Sync without a preview?").assertDoesNotExist()
    }

    @Test
    fun review_syncWithoutPreview_isDisabledWhileASyncRuns() {
        reviewDialog(FirstSyncPreviewState.Failed("timed out"), syncing = true)
        composeTestRule.onNodeWithText("Sync without preview").performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("sync")
        composeTestRule.onAllNodesWithText("Sync without preview")[1].assertIsNotEnabled()
        composeTestRule.onAllNodesWithText("A sync is running. Wait for it to finish.").assertCountEquals(1)
    }

    @Test
    fun review_openDetails_isShownOnlyWhenAHandlerIsGiven() {
        var opened = 0
        reviewDialog(null, onOpenDetails = { opened++ })
        composeTestRule.onNodeWithText("Open details").performClick()
        assertEquals(1, opened)
    }
}
