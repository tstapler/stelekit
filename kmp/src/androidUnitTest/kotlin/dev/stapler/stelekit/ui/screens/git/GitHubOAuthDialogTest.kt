// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Robolectric Compose tests for [GitHubOAuthDialog] across each [OAuthDialogState]. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class GitHubOAuthDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val farFuture = 4_102_444_800_000L // year 2100

    private class Calls {
        val copied = mutableListOf<String>()
        val opened = mutableListOf<String>()
        var cancel = 0
        var retry = 0
        var done = 0
    }

    private fun show(state: OAuthDialogState): Calls {
        val c = Calls()
        composeTestRule.setContent {
            MaterialTheme {
                GitHubOAuthDialog(
                    state = state,
                    onCopyCode = { c.copied += it },
                    onOpenBrowser = { c.opened += it },
                    onCancel = { c.cancel++ },
                    onRetry = { c.retry++ },
                    onDone = { c.done++ },
                )
            }
        }
        return c
    }

    @Test
    fun dialog_should_showLoadingAndCancel_when_loading() {
        val c = show(OAuthDialogState.Loading)
        composeTestRule.onNodeWithText("Connect GitHub Account").assertExists()
        composeTestRule.onNodeWithText("Requesting code from GitHub…").assertExists()
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, c.cancel)
    }

    @Test
    fun dialog_should_showCodeAndFireCallbacks_when_showCode() {
        val c = show(OAuthDialogState.ShowCode("ABCD-1234", "https://github.com/login/device", farFuture))
        composeTestRule.onNodeWithText("ABCD-1234").assertExists()
        composeTestRule.onNodeWithText("Expires in", substring = true).assertExists()
        composeTestRule.onNodeWithText("Copy code").performClick()
        composeTestRule.onNodeWithText("Open GitHub").performClick()
        assertEquals(listOf("ABCD-1234"), c.copied)
        assertEquals(listOf("https://github.com/login/device"), c.opened)
    }

    @Test
    fun dialog_should_showWaitingLabel_when_polling() {
        show(OAuthDialogState.Polling("ZZZZ", "https://github.com/login/device", farFuture))
        composeTestRule.onNodeWithText("Waiting for authorization…").assertExists()
        composeTestRule.onNodeWithText("ZZZZ").assertExists()
    }

    @Test
    fun dialog_should_showExpiredCountdown_when_expiresAtInPast() {
        show(OAuthDialogState.ShowCode("OLD", "u", 0L))
        composeTestRule.onNodeWithText("Expires in 0m 00s").assertExists()
    }

    @Test
    fun dialog_should_showUsernameAndDone_when_success() {
        val c = show(OAuthDialogState.Success("octocat"))
        composeTestRule.onNodeWithText("Connected as @octocat").assertExists()
        composeTestRule.onNodeWithContentDescription("Authorization successful").assertExists()
        assertTrue(composeTestRule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isEmpty())
        composeTestRule.onNodeWithText("Done").performClick()
        assertEquals(1, c.done)
    }

    @Test
    fun dialog_should_showErrorAndRetry_when_error() {
        val c = show(OAuthDialogState.Error("access_denied"))
        composeTestRule.onNodeWithText("access_denied").assertExists()
        composeTestRule.onNodeWithContentDescription("Authorization error").assertExists()
        composeTestRule.onNodeWithText("Try Again").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, c.retry)
        assertEquals(1, c.cancel)
    }
}
