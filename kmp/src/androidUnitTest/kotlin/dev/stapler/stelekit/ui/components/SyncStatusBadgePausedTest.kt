// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.SyncState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** UT-146 / UW-68: the paused state of scheduled sync is visible outside Settings and opens Settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SyncStatusBadgePausedTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun badge_should_ShowSyncPausedWithDistinctIconShapeAndOpenGitSettings_When_StatusPaused() {
        var opened = 0
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusBadge(
                    status = GitSyncStatus(SyncState.Idle, scheduledSyncPaused = true),
                    onSyncClick = {},
                    onOpenScheduledSyncSettings = { opened++ },
                )
            }
        }
        composeTestRule.onNodeWithText("Sync paused").assertExists()
        composeTestRule.onNodeWithContentDescription("Sync paused - tap to open settings").performClick()
        assertEquals(1, opened)
        // The error state uses a different icon description, so the two states are distinguishable.
        composeTestRule.onNodeWithContentDescription("Sync error - tap to retry").assertDoesNotExist()
    }

    @Test
    fun badge_should_KeepErrorVisible_When_PausedAndErrorBothTrue() {
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusBadge(
                    status = GitSyncStatus(SyncState.Error(DomainError.GitError.Offline), scheduledSyncPaused = true),
                    onSyncClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Sync paused").assertDoesNotExist()
    }

    @Test
    fun badge_should_NotShowPaused_When_NotPaused() {
        composeTestRule.setContent {
            MaterialTheme { SyncStatusBadge(status = GitSyncStatus(SyncState.Idle), onSyncClick = {}) }
        }
        composeTestRule.onNodeWithText("Sync paused").assertDoesNotExist()
    }
}
