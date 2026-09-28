// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.git.model.SyncState
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock

/**
 * Compose-layer unit tests for [SyncStateBadge]'s `LocalChangesPending` and `RateLimited`
 * branches (Epic 5.1, Stories 5.1.1/5.1.2 of the web-git-writeback plan).
 */
class SyncStatusBadgeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun syncStateBadge_rendersDistinctBadge_forLocalChangesPendingState() {
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusBadge(
                    status = GitSyncStatus(SyncState.LocalChangesPending(fileCount = 2)),
                    onSyncClick = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("2 unsynced changes").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 unsynced").assertIsDisplayed()
    }

    @Test
    fun syncStateBadge_tapInvokesOnSyncClick_forLocalChangesPendingState() {
        var clickCount = 0
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusBadge(
                    status = GitSyncStatus(SyncState.LocalChangesPending(fileCount = 2)),
                    onSyncClick = { clickCount++ },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("2 unsynced changes").performClick()

        assertEquals(1, clickCount)
    }

    @Test
    fun syncStateBadge_neverContainsTapToRetryCopy_forRateLimitedState() {
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusBadge(
                    status = GitSyncStatus(SyncState.RateLimited(retryAfterSeconds = 5)),
                    onSyncClick = {},
                )
            }
        }

        val node = composeTestRule
            .onNodeWithContentDescription("Rate limited — retrying automatically")
            .fetchSemanticsNode()
        val contentDescription = node.config
            .getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            .joinToString(" ")
        val textNode = composeTestRule.onNodeWithText("Retrying…").fetchSemanticsNode()
        val text = textNode.config
            .getOrElse(SemanticsProperties.Text) { emptyList() }
            .joinToString(" ") { it.text }
        val combined = "$contentDescription $text"

        assertFalse(
            combined.contains("tap to retry", ignoreCase = true),
            "RateLimited copy must never contain 'tap to retry', but was: \"$combined\"",
        )
        composeTestRule.onNodeWithContentDescription("Rate limited — retrying automatically").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retrying…").assertIsDisplayed()
    }

    @Test
    fun syncStateBadge_tapIsNoOpAndDoesNotInvokeOnSyncClick_forRateLimitedState() {
        var clickCount = 0
        val statusHolder = mutableStateOf(GitSyncStatus(SyncState.RateLimited(retryAfterSeconds = 5)))
        var currentStatus by statusHolder

        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusBadge(
                    status = currentStatus,
                    onSyncClick = { clickCount++ },
                )
            }
        }

        // Tapping the RateLimited row must not invoke onSyncClick.
        composeTestRule.onNodeWithContentDescription("Rate limited — retrying automatically").performClick()
        assertEquals(0, clickCount)

        // A later transition to another actionable state restores normal tap behavior —
        // the no-op is scoped strictly to RateLimited.
        currentStatus = GitSyncStatus(SyncState.LocalChangesPending(fileCount = 1))
        composeTestRule.onNodeWithContentDescription("1 unsynced changes").performClick()
        assertEquals(1, clickCount)
    }

    @Test
    fun syncStateBadge_showsNeverSynced_whenIdleWithNullLastSyncAt() {
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusBadge(
                    status = GitSyncStatus(state = SyncState.Idle, lastSyncAt = null),
                    onSyncClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Never synced").assertIsDisplayed()
    }

    @Test
    fun syncStateBadge_showsSyncedNMinutesAgo_whenIdleWithPastLastSyncAt() {
        val fiveMinutesAgo = Clock.System.now().toEpochMilliseconds() - 5 * 60_000L
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusBadge(
                    status = GitSyncStatus(state = SyncState.Idle, lastSyncAt = fiveMinutesAgo),
                    onSyncClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Synced 5m ago").assertIsDisplayed()
    }
}
