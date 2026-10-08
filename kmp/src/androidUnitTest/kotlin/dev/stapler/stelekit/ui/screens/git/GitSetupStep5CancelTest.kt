// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.git.CloneProgress
import dev.stapler.stelekit.git.GitTransportRetryState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * Robolectric Compose-behavior tests for `Step5TestAndSave`'s Cancel affordance and post-cancel
 * state (git-sync-resilience Story 4.1.4). Covers `validation.md`'s Story 4.1.4 rows and UX
 * Acceptance Test criteria 1, 4, 8, 10, 13, 16, 18.
 */
@RunWith(RobolectricTestRunner::class)
class GitSetupStep5CancelTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setStep5(
        cloneInProgress: Boolean = false,
        retryState: GitTransportRetryState = GitTransportRetryState.Idle,
        cloneCancelled: Boolean = false,
        onCancelClone: () -> Unit = {},
        onSave: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                Step5TestAndSave(
                    testState = GitConnectionTestState.Idle,
                    saving = false,
                    saveError = null,
                    onBack = {},
                    onTestConnection = {},
                    onCancelTestConnection = {},
                    cloneInProgress = cloneInProgress,
                    retryState = retryState,
                    cloneCancelled = cloneCancelled,
                    onSave = onSave,
                    onCancelClone = onCancelClone,
                )
            }
        }
    }

    // ── UX Acceptance Test 1/10 ──────────────────────────────────────────────────────────────

    @Test
    fun `Cancel button is visible and enabled during Attempting, with no confirmation dialog`() {
        setStep5(cloneInProgress = true, retryState = GitTransportRetryState.Attempting(CloneProgress("", 0, 0)))

        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `Cancel button is visible and enabled during Retrying`() {
        setStep5(cloneInProgress = true, retryState = GitTransportRetryState.Retrying(1, 4, null))

        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `tapping Cancel invokes onCancelClone exactly once`() {
        var cancelCalls = 0
        setStep5(cloneInProgress = true, retryState = GitTransportRetryState.Attempting(CloneProgress("", 0, 0)), onCancelClone = { cancelCalls++ })

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(1, cancelCalls)
    }

    @Test
    fun `Back and Save remain disabled while cloneInProgress`() {
        setStep5(cloneInProgress = true, retryState = GitTransportRetryState.Attempting(CloneProgress("", 0, 0)))

        composeTestRule.onNodeWithText("Back").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Save configuration").assertIsNotEnabled()
    }

    // ── UX Acceptance Test 16 — distinct accessible label from the Test Connection row's Cancel ──

    @Test
    fun `the in-progress-clone Cancel button has a distinct Cancel clone content description`() {
        setStep5(cloneInProgress = true, retryState = GitTransportRetryState.Attempting(CloneProgress("", 0, 0)))

        composeTestRule.onNodeWithContentDescription("Cancel clone").assertIsDisplayed()
    }

    // ── UX Acceptance Test 8/13 — post-cancel copy and exit paths ───────────────────────────────

    @Test
    fun `after cancellation, the row shows exactly the cancelled copy`() {
        setStep5(cloneCancelled = true)

        composeTestRule.onNodeWithText("Cancelled — your progress is saved. Resume anytime from Step 5.").assertIsDisplayed()
    }

    @Test
    fun `Cancelled enables both Save configuration and Back`() {
        setStep5(cloneCancelled = true)

        composeTestRule.onNodeWithText("Back").assertIsEnabled()
        composeTestRule.onNodeWithText("Save configuration").assertIsEnabled()
    }

    // ── UX Acceptance Test 4 — resuming a cancelled clone re-invokes Save configuration ──────────

    @Test
    fun `after Cancelled, tapping Save configuration re-invokes onSave with no intermediate confirmation UI`() {
        var saveCalls = 0
        setStep5(cloneCancelled = true, onSave = { saveCalls++ })

        composeTestRule.onNodeWithText("Save configuration").performClick()

        assertEquals(1, saveCalls)
    }
}
