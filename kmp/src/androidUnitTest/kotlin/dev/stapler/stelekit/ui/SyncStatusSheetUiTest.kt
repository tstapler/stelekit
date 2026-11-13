// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.git.StalenessChipState
import dev.stapler.stelekit.git.StalenessSeverity
import dev.stapler.stelekit.git.SyncStaleness
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.syncStatusSheetModel
import dev.stapler.stelekit.ui.components.GitSyncStatus
import dev.stapler.stelekit.ui.components.SyncStatusBadge
import dev.stapler.stelekit.ui.components.SyncStatusSheetActions
import dev.stapler.stelekit.ui.components.SyncStatusSheetContent
import dev.stapler.stelekit.ui.components.settings.ScheduledSyncSection
import dev.stapler.stelekit.ui.components.settings.ScheduledSyncSettingsState
import dev.stapler.stelekit.ui.components.settings.VIEW_STATUS_LABEL
import dev.stapler.stelekit.git.ScheduledSyncStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SyncStatusSheetUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val now = 10_000_000L
    private val minute = 60_000L

    private class Taps {
        var syncNow = 0
        var review = 0
        var settings = 0
        var dismiss = 0
        var problem = 0
    }

    private fun actions(taps: Taps) = SyncStatusSheetActions(
        onSyncNow = { taps.syncNow++ },
        onReviewFirstSync = { taps.review++ },
        onOpenSettings = { taps.settings++ },
        onDismiss = { taps.dismiss++ },
        onOpenProblem = { taps.problem++ },
    )

    private fun show(
        staleness: SyncStaleness,
        taps: Taps = Taps(),
        syncState: SyncState = SyncState.Idle,
        reviewPending: Boolean = false,
        notice: String? = null,
    ) {
        val model = syncStatusSheetModel(staleness, syncState, now, 15, reviewPending)
        composeTestRule.setContent { MaterialTheme { SyncStatusSheetContent(model, actions(taps), notice = notice) } }
    }

    @Test
    fun sheet_showsTheChipHeadlineTheRowsAndTheRecentChecks() {
        show(SyncStaleness(lastMergedAtMs = now - 5 * minute, lastFetchedAtMs = now - minute, behindCount = 3))

        composeTestRule.onNodeWithText("last merged 5 min ago · behind 3").assertExists()
        composeTestRule.onNodeWithText("3 commits").assertExists()
        composeTestRule.onNodeWithText("1 min ago").assertExists()
        composeTestRule.onNodeWithText("Updates are waiting on the remote.").assertExists()
    }

    @Test
    fun sheet_buttonsForwardAndNeverStartASyncExceptSyncNow() {
        val taps = Taps()
        show(SyncStaleness(behindCount = 1), taps, reviewPending = true)

        composeTestRule.onNodeWithText("Review first sync").performClick()
        composeTestRule.onNodeWithText("Open sync settings").performClick()
        composeTestRule.onNodeWithText("Close").performClick()
        assertEquals(listOf(0, 1, 1, 1), listOf(taps.syncNow, taps.review, taps.settings, taps.dismiss))

        composeTestRule.onNodeWithText("Sync now").performClick()
        assertEquals(1, taps.syncNow)
    }

    @Test
    fun sheet_disablesSyncNowWhileASyncRunsAndHidesReviewWhenNotOwed() {
        show(SyncStaleness(), syncState = SyncState.Merging)

        composeTestRule.onNodeWithText("Sync now").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Review first sync").assertDoesNotExist()
    }

    @Test
    fun sheet_showsAConflictAndLetsTheOwnerResolveIt() {
        val taps = Taps()
        val conflict = SyncState.ConflictPending(listOf(dev.stapler.stelekit.git.model.ConflictFile("a", "pages/a.md", emptyList())))
        show(SyncStaleness(behindCount = 1), taps, syncState = conflict)

        composeTestRule.onNodeWithText("Merge conflict in 1 file. Nothing is merged until you resolve it.").assertExists()
        composeTestRule.onNodeWithText("Sync now").assertIsEnabled()
        composeTestRule.onNodeWithText("Resolve conflicts").performClick()
        assertEquals(1, taps.problem)
    }

    @Test
    fun sheet_explainsWhySyncNowIsDisabled() {
        show(SyncStaleness(), syncState = SyncState.Merging)

        composeTestRule.onNodeWithText("A sync is already running. Sync now is available once it finishes.").assertExists()
    }

    @Test
    fun sheet_showsAFailureWithNoActionWhenThereIsNothingToOpen() {
        show(SyncStaleness(), syncState = SyncState.Error(dev.stapler.stelekit.error.DomainError.GitError.Offline))

        composeTestRule.onNodeWithText("Offline — sync will resume when connected", substring = true).assertExists()
        composeTestRule.onNodeWithText("Resolve conflicts").assertDoesNotExist()
    }

    @Test
    fun sheet_showsTheNoticeForAVanishedGraph() {
        show(SyncStaleness(behindCount = 0), notice = "Updates are waiting for Notes")

        composeTestRule.onNodeWithText("Updates are waiting for Notes").assertExists()
        composeTestRule.onNodeWithText("Sync now").assertIsEnabled()
    }

    @Test
    fun chip_isTappableAndOpensTheSheetWhenAHandlerIsGiven() {
        var opened = 0
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusBadge(
                    status = GitSyncStatus(SyncState.Idle, stalenessChip = StalenessChipState("no merge yet · behind 2", StalenessSeverity.Healthy)),
                    onSyncClick = {},
                    onOpenSyncStatus = { opened++ },
                )
            }
        }

        composeTestRule.onNodeWithText("no merge yet · behind 2").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun settings_offersViewSyncStatusForAGraphWithARemote() {
        var opened = 0
        val state = ScheduledSyncSettingsState(
            hasRemote = true, enabled = false, configuredIntervalMinutes = 15, minimumIntervalMinutes = 1,
            closedAppChecks = false, backgroundRestricted = false, status = ScheduledSyncStatus(),
            onEnabledChange = {}, onIntervalChange = {}, onResume = {}, onSetUpGit = {}, onFix = {},
            onOpenStatus = { opened++ },
        )
        composeTestRule.setContent { MaterialTheme { ScheduledSyncSection(state, nowMs = now) } }

        composeTestRule.onNodeWithText(VIEW_STATUS_LABEL).performClick()
        assertEquals(1, opened)
    }
}
