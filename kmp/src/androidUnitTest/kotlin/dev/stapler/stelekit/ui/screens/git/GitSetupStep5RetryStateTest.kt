// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import dev.stapler.stelekit.git.CloneProgress
import dev.stapler.stelekit.git.GitTransportRetryState
import dev.stapler.stelekit.git.NonRetryableReason
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Robolectric Compose-behavior tests for `Step5TestAndSave`'s retry/terminal-state rendering
 * (git-sync-resilience Story 4.1.3) — per `CLAUDE.md`'s testing-best-practices, a Compose-behavior
 * test that doesn't need true pixel rendering belongs here (androidUnitTest/Robolectric), not
 * jvmTest/Roborazzi. Covers `validation.md`'s Story 4.1.3 rows and several of its 20 UX Acceptance
 * Test criteria (5, 6, 7, 9, 11, 12, 14, 17).
 */
@RunWith(RobolectricTestRunner::class)
class GitSetupStep5RetryStateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setStep5(
        retryState: GitTransportRetryState = GitTransportRetryState.Idle,
        cloneInProgress: Boolean = false,
        cloneCancelled: Boolean = false,
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
                )
            }
        }
    }

    // ── UX Acceptance Test 5 ─────────────────────────────────────────────────────────────────

    @Test
    fun `Retrying renders primary Cloning your graph and secondary Reconnecting Attempt 2 of 4, with no raw exception text`() {
        setStep5(cloneInProgress = true, retryState = GitTransportRetryState.Retrying(attempt = 2, max = 4, progress = null))

        composeTestRule.onNodeWithText("Cloning your graph…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reconnecting… Attempt 2 of 4").assertIsDisplayed()
        composeTestRule.onRoot().printToString().let { tree ->
            assertFalse(tree.contains("Exception"), "must never render a raw exception class name")
            assertFalse(tree.contains("Software caused"), "must never render a raw exception message")
        }
    }

    @Test
    fun `Retrying with known progress appends the percent clause to the secondary line`() {
        setStep5(
            cloneInProgress = true,
            retryState = GitTransportRetryState.Retrying(attempt = 1, max = 5, progress = CloneProgress("Receiving objects", 45, 100)),
        )

        composeTestRule.onNodeWithText("Reconnecting… Attempt 1 of 5 — 45%").assertIsDisplayed()
    }

    // ── UX Acceptance Test 6 ─────────────────────────────────────────────────────────────────

    @Test
    fun `Exhausted renders the exact warning copy with a Warning icon and a Try again action`() {
        setStep5(retryState = GitTransportRetryState.Exhausted("network down", maxAttempts = 5))

        composeTestRule.onNodeWithText(
            "Couldn't finish after 5 attempts. Check your connection and try again — your progress is saved."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Warning").assertIsDisplayed()
        composeTestRule.onNodeWithText("Try again").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `Exhausted's copy templates the attempt count from state, not a hardcoded literal`() {
        setStep5(retryState = GitTransportRetryState.Exhausted("network down", maxAttempts = 7))

        composeTestRule.onNodeWithText(
            "Couldn't finish after 7 attempts. Check your connection and try again — your progress is saved."
        ).assertIsDisplayed()
    }

    @Test
    fun `tapping Try again after Exhausted re-invokes the same onSave entry point, not a special-cased retry function`() {
        var saveCalls = 0
        setStep5(retryState = GitTransportRetryState.Exhausted("network down"), onSave = { saveCalls++ })

        composeTestRule.onNodeWithText("Try again").performClick()

        assertEquals(1, saveCalls)
    }

    // ── UX Acceptance Test 7 ─────────────────────────────────────────────────────────────────

    @Test
    fun `NonRetryableFailure for auth shows the auth copy, an Error icon, and no attempt counter`() {
        setStep5(retryState = GitTransportRetryState.NonRetryableFailure(NonRetryableReason.AUTH))

        composeTestRule.onNodeWithText("Authentication failed — check your token/SSH key in Step 3.").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Error").assertIsDisplayed()
        composeTestRule.onRoot().printToString().let { tree ->
            assertFalse(Regex("""Attempt \d+ of \d+""").containsMatchIn(tree), "must show no attempt counter for a non-retryable failure")
        }
    }

    @Test
    fun `NonRetryableFailure for not-found shows the repo-not-found copy naming the previous step`() {
        setStep5(retryState = GitTransportRetryState.NonRetryableFailure(NonRetryableReason.NOT_FOUND))

        composeTestRule.onNodeWithText("Repository not found — check the URL on the previous step.").assertIsDisplayed()
    }

    @Test
    fun `NonRetryableFailure shows no Try again button`() {
        setStep5(retryState = GitTransportRetryState.NonRetryableFailure(NonRetryableReason.AUTH))

        composeTestRule.onRoot().printToString().let { tree ->
            assertFalse(tree.contains("Try again"), "NonRetryableFailure must not offer Try again")
        }
    }

    // ── UX Acceptance Test 11/12 (exit paths — Back/Save gating) ────────────────────────────────

    @Test
    fun `Exhausted enables Back, Save configuration, and Try again`() {
        setStep5(retryState = GitTransportRetryState.Exhausted("network down"))

        composeTestRule.onNodeWithText("Back").assertIsEnabled()
        composeTestRule.onNodeWithText("Save configuration").assertIsEnabled()
        composeTestRule.onNodeWithText("Try again").assertIsEnabled()
    }

    @Test
    fun `NonRetryableFailure enables Back and Save but shows no Try again`() {
        setStep5(retryState = GitTransportRetryState.NonRetryableFailure(NonRetryableReason.AUTH))

        composeTestRule.onNodeWithText("Back").assertIsEnabled()
        composeTestRule.onNodeWithText("Save configuration").assertIsEnabled()
    }

    // ── UX Acceptance Test 14 — live region ──────────────────────────────────────────────────

    @Test
    fun `the in-progress row carries liveRegion Polite semantics, matching the FolderSyncReconciliationProgress convention`() {
        setStep5(cloneInProgress = true, retryState = GitTransportRetryState.Attempting(CloneProgress("Receiving objects", 0, 0)))

        val liveRegionNode = composeTestRule.onNode(
            SemanticsMatcher("has liveRegion=Polite") { node ->
                node.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite
            }
        )
        liveRegionNode.assertExists()
    }

    // ── UX Acceptance Test 17 — distinct terminal-state icon contentDescriptions ─────────────────

    @Test
    fun `Exhausted's icon has contentDescription Warning`() {
        setStep5(retryState = GitTransportRetryState.Exhausted("x"))
        composeTestRule.onNodeWithContentDescription("Warning").assertExists()
    }

    @Test
    fun `NonRetryableFailure's icon has contentDescription Error, distinct from Exhausted's Warning`() {
        setStep5(retryState = GitTransportRetryState.NonRetryableFailure(NonRetryableReason.AUTH))
        composeTestRule.onNodeWithContentDescription("Error").assertExists()
    }

    // ── Attempting: primary text only, no secondary line, no attempt counter ────────────────────

    @Test
    fun `Attempting shows only the primary Cloning your graph line, no secondary text`() {
        setStep5(cloneInProgress = true, retryState = GitTransportRetryState.Attempting(CloneProgress("Receiving objects", 0, 0)))

        composeTestRule.onNodeWithText("Cloning your graph…").assertIsDisplayed()
        composeTestRule.onRoot().printToString().let { tree ->
            assertFalse(tree.contains("Reconnecting"), "Attempting must not show Retrying's secondary line")
        }
    }
}
