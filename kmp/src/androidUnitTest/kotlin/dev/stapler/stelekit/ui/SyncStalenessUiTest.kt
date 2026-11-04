// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.git.Banner
import dev.stapler.stelekit.git.StalenessChipState
import dev.stapler.stelekit.git.StalenessSeverity
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.ui.components.GitSyncStatus
import dev.stapler.stelekit.ui.components.SyncLaunchBanner
import dev.stapler.stelekit.ui.components.SyncStatusBadge
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SyncStalenessUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun chip_isShownBesideTheBadge() {
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusBadge(
                    status = GitSyncStatus(SyncState.Idle, stalenessChip = StalenessChipState("fetch-only (SAF) · behind 4", StalenessSeverity.Amber)),
                    onSyncClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText("fetch-only (SAF) · behind 4").assertExists()
    }

    @Test
    fun chip_isAbsentWhenThereIsNoSyncService() {
        composeTestRule.setContent { MaterialTheme { SyncStatusBadge(status = GitSyncStatus(SyncState.Idle), onSyncClick = {}) } }
        composeTestRule.onNodeWithText("behind", substring = true).assertDoesNotExist()
    }

    @Test
    fun badge_showsReviewFirstSyncForAnUnconfirmedConfigAndStillShowsTheChip() {
        var reviews = 0
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusBadge(
                    status = GitSyncStatus(
                        SyncState.Idle,
                        firstSyncReviewPending = true,
                        stalenessChip = StalenessChipState("no merge yet · behind 2", StalenessSeverity.Healthy),
                    ),
                    onSyncClick = {},
                    onReviewFirstSync = { reviews++ },
                )
            }
        }

        composeTestRule.onNodeWithText("no merge yet · behind 2").assertExists()
        composeTestRule.onNodeWithText("Review first sync").performClick()
        assertEquals(1, reviews)
    }

    @Test
    fun updatesWaitingBanner_namesTheCountAndOffersReviewAndDismiss() {
        var reviews = 0
        var dismissals = 0
        composeTestRule.setContent {
            MaterialTheme { SyncLaunchBanner(Banner.UpdatesWaiting, 3, onReview = { reviews++ }, onDismiss = { dismissals++ }) }
        }

        composeTestRule.onNodeWithText("Updates are waiting — 3 remote commits not merged yet.").assertExists()
        composeTestRule.onNodeWithText("Review").performClick()
        composeTestRule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, reviews)
        assertEquals(1, dismissals)
    }

    @Test
    fun updatesWaitingBanner_usesTheSingularForOneCommit() {
        composeTestRule.setContent { MaterialTheme { SyncLaunchBanner(Banner.UpdatesWaiting, 1, {}, {}) } }
        composeTestRule.onNodeWithText("Updates are waiting — 1 remote commit not merged yet.").assertExists()
    }

    @Test
    fun firstSyncBanner_hasItsOwnCopy_andMismatchRendersNothing() {
        composeTestRule.setContent {
            MaterialTheme {
                SyncLaunchBanner(Banner.FirstSyncReview, 0, {}, {})
                SyncLaunchBanner(Banner.Mismatch, 0, {}, {})
            }
        }
        composeTestRule.onNodeWithText("Review the first sync before SteleKit syncs this graph for you.").assertExists()
        composeTestRule.onNodeWithText("Dismiss").assertExists() // exactly one banner rendered
    }
}
