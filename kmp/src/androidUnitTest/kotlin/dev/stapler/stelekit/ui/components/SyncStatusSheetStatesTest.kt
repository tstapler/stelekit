// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.LockVerdict
import dev.stapler.stelekit.git.PausedRetryInfo
import dev.stapler.stelekit.git.SyncOrigin
import dev.stapler.stelekit.git.SyncRunInfo
import dev.stapler.stelekit.git.SyncStaleness
import dev.stapler.stelekit.git.SyncStatusSheetModel
import dev.stapler.stelekit.git.UnknownReason
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.syncStatusSheetModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Task 1.7b: the sheet binds the Running, Paused and Failed states, Retry now, the live region and the lock-removal confirm. */
@RunWith(RobolectricTestRunner::class)
class SyncStatusSheetStatesTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val now = 10_000_000L
    private val unknown = SyncState.Error(
        DomainError.GitError.StaleLockFile("repo/.git/index.lock", LockVerdict.OwnerUnknown(UnknownReason.RecoveryLockUnsupported)),
    )
    private val noRecord = SyncState.Error(DomainError.GitError.StaleLockFile("repo/.git/index.lock", LockVerdict.NoRecord))

    private class Taps {
        var syncNow = 0
        var retryNow = 0
        var removeLock = 0
    }

    private fun actions(taps: Taps) = SyncStatusSheetActions(
        onSyncNow = { taps.syncNow++ },
        onReviewFirstSync = {},
        onOpenSettings = null,
        onDismiss = {},
        onRemoveLockFile = { taps.removeLock++ },
    )

    private fun paused(taps: Taps, state: SyncState = unknown) = syncStatusSheetModel(
        SyncStaleness(), state, now, 15, false,
        pausedRetry = PausedRetryInfo(now + 30_000, 1), retryNow = { taps.retryNow++ },
    )

    private fun failed(state: SyncState = unknown) = syncStatusSheetModel(SyncStaleness(), state, now, 15, false, pausedRetry = null)

    private fun show(model: SyncStatusSheetModel, taps: Taps = Taps(), announcement: String? = null, stopping: Boolean = false) {
        composeTestRule.setContent { MaterialTheme { SyncStatusSheetContent(model, actions(taps), announcement = announcement, stopping = stopping) } }
    }

    @Test
    fun running_should_ShowSyncingDisabledWithAReasonAndNoRedElement() {
        val model = syncStatusSheetModel(
            SyncStaleness(), SyncState.Fetching, now, 15, false,
            inFlight = SyncRunInfo(SyncOrigin.UiPress, now, "g", followUpQueued = true),
        )
        show(model)

        composeTestRule.onNodeWithText("Syncing...", useUnmergedTree = true).assertExists()
        composeTestRule.onNode(hasText("Syncing...") and androidx.compose.ui.test.hasClickAction()).assertIsNotEnabled()
        composeTestRule.onNodeWithText("Syncing... (Fetching)").assertExists()
        composeTestRule.onNodeWithText("A sync is already running. Sync now is available once it finishes.").assertExists()
        composeTestRule.onNodeWithText("One more sync will run after this").assertExists()
        composeTestRule.onNodeWithText("Sync now").assertDoesNotExist()
        composeTestRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "A sync is already running")).assertExists()
    }

    @Test
    fun running_should_OmitTheFollowUpLine_When_NoPressIsQueued() {
        show(syncStatusSheetModel(SyncStaleness(), SyncState.Fetching, now, 15, false, inFlight = SyncRunInfo(SyncOrigin.UiPress, now, "g")))

        composeTestRule.onNodeWithText("One more sync will run after this").assertDoesNotExist()
    }

    @Test
    fun running_should_ShowADisabledStoppingButton_When_CancelIsWaitingOnAGitCall() {
        show(syncStatusSheetModel(SyncStaleness(), SyncState.Committing, now, 15, false), stopping = true)

        composeTestRule.onNodeWithText("Stopping...").assertIsNotEnabled()
    }

    @Test
    fun paused_should_ShowCauseNeutralCopyAndAnEnabledRetryNowThatForwards() {
        val taps = Taps()
        show(paused(taps), taps)

        composeTestRule.onNodeWithText("Sync paused").assertExists()
        composeTestRule.onNodeWithText("We couldn't confirm that nothing else is syncing", substring = true).assertExists()
        composeTestRule.onNodeWithText("another git operation", substring = true, ignoreCase = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Retry now").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, taps.retryNow)
        composeTestRule.onNodeWithText("Sync now").assertDoesNotExist()
        composeTestRule.onNodeWithText("Try again", substring = true).assertDoesNotExist()
    }

    @Test
    fun paused_details_should_ShowTheTryNumberAndNoCause() {
        show(paused(Taps()))

        composeTestRule.onNodeWithText("Details").performClick()

        composeTestRule.onNodeWithText("Lock owner unknown, try 1 of 2").assertExists()
    }

    @Test
    fun failed_should_ShowTheErrorTextWithSyncNowAndNoRetryNow() {
        val taps = Taps()
        show(failed(), taps)

        composeTestRule.onNodeWithText("Couldn't sync. Your notes are saved on this device.").assertExists()
        composeTestRule.onNodeWithText("Retry now").assertDoesNotExist()
        composeTestRule.onNodeWithText("Sync now").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, taps.syncNow)
        composeTestRule.onNodeWithText("Try again", substring = true).assertDoesNotExist()
    }

    @Test
    fun announcement_should_BeAPoliteLiveRegion() {
        show(paused(Taps()), announcement = "Sync paused. Retrying automatically.")

        composeTestRule.onNode(
            hasContentDescription("Sync paused. Retrying automatically.") and
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        ).assertExists()
    }

    @Test
    fun removeLock_should_BeConfirmGatedWithInitialFocusOnCancel_AndOnlyForNoRecord() {
        val taps = Taps()
        show(paused(taps, noRecord), taps)
        composeTestRule.onNodeWithText("Details").performClick()

        composeTestRule.onNodeWithText("Remove lock file...").performScrollTo().performClick()

        composeTestRule.onNodeWithText("Do this only if no other sync or git app is running", substring = true).assertExists()
        composeTestRule.onNodeWithText("Cancel").assertIsFocused()
        assertEquals(0, taps.removeLock, "nothing is removed until confirmed")
        composeTestRule.onNodeWithText("Remove").performClick()
        assertEquals(1, taps.removeLock)
    }

    @Test
    fun removeLock_should_NotBeOffered_When_TheOwnerIsUnknown() {
        show(paused(Taps()))
        composeTestRule.onNodeWithText("Details").performClick()

        composeTestRule.onNodeWithText("Remove lock file...").assertDoesNotExist()
    }

    @Test
    fun oneTapRows_should_NeverUseLockFileJargon() {
        show(paused(Taps()))

        composeTestRule.onNodeWithText("index.lock", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("stale", substring = true, ignoreCase = true).assertDoesNotExist()
    }
}
