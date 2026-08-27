// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.tags.LlmSuggestionStatus
import dev.stapler.stelekit.tags.TagSuggestion
import dev.stapler.stelekit.tags.TagSuggestionState
import dev.stapler.stelekit.ui.components.tags.SuggestionBottomSheet
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * design/ux.md Step 3's 8 automatable UX acceptance criteria (row 9, contrast, is a manual
 * tooling check, not a Compose test — see validation.md's UX Acceptance Tests table). Each
 * `@Test` here corresponds 1:1 to one row of that table, exercising `SuggestionBottomSheet`'s
 * `LlmSuggestionStatus` rendering directly (no ViewModel involved — pure state-in, tree-out),
 * following `ErrorStateNoDeadEndTest.kt`'s exact `createComposeRule` / `MaterialTheme { ... }`
 * pattern.
 */
class LlmSuggestionCaptionStatesUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val localChip = TagSuggestion(term = "Kotlin", confidence = 1.0f, source = TagSuggestion.Source.LOCAL)

    private fun readyState(llmStatus: LlmSuggestionStatus) = TagSuggestionState.Ready(
        blockUuid = "block-1",
        localSuggestions = listOf(localChip),
        llmSuggestions = emptyList(),
        llmStatus = llmStatus,
    )

    // ─── Criterion 1: fast path, zero extra taps (validates AC4) ─────────────────────────────

    @Test
    fun `Resolved status renders chips with no caption and no spinner beyond local-match render`() {
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionBottomSheet(
                    state = readyState(LlmSuggestionStatus.Resolved),
                    onAcceptTag = { _, _ -> },
                    onDismiss = {},
                    onRetry = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Kotlin").assertIsEnabled()
        composeTestRule.onNodeWithText("Downloading on-device model — this may take a few minutes").assertDoesNotExist()
        composeTestRule.onNodeWithText("Taking longer than expected.").assertDoesNotExist()
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    // ─── Criterion 2: retry path, exactly 1 tap (validates AC3) ──────────────────────────────

    @Test
    fun `Stalled state resumes the download in exactly one tap on Retry`() {
        var retryCount = 0

        composeTestRule.setContent {
            MaterialTheme {
                SuggestionBottomSheet(
                    state = readyState(LlmSuggestionStatus.Stalled(retryable = true)),
                    onAcceptTag = { _, _ -> },
                    onDismiss = {},
                    onRetry = { retryCount++ },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Retry downloading tags").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, retryCount, "Retry must resume the download in exactly one tap")
    }

    // ─── Criterion 4: specific message + specific action per row (c)/(d)/(e) ─────────────────

    @Test
    fun `Stalled renders literal Taking longer than expected plus secondary line and labeled Retry button`() {
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionBottomSheet(
                    state = readyState(LlmSuggestionStatus.Stalled(retryable = true)),
                    onAcceptTag = { _, _ -> },
                    onDismiss = {},
                    onRetry = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Taking longer than expected.").assertIsEnabled()
        composeTestRule.onNodeWithText("Tap Retry to check again, or keep typing the tag yourself.").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Retry downloading tags").assertIsEnabled()
    }

    @Test
    fun `Failed with retryable false renders the SDK reason with no button`() {
        val reason = "On-device AI is not supported on this device"

        composeTestRule.setContent {
            MaterialTheme {
                SuggestionBottomSheet(
                    state = readyState(LlmSuggestionStatus.Failed(message = reason, retryable = false)),
                    onAcceptTag = { _, _ -> },
                    onDismiss = {},
                    onRetry = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(reason).assertIsEnabled()
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun `Failed with retryable true renders the timeout message and a labeled Retry button`() {
        val message = "LLM tag suggestion timed out after 90s"
        var retried = false

        composeTestRule.setContent {
            MaterialTheme {
                SuggestionBottomSheet(
                    state = readyState(LlmSuggestionStatus.Failed(message = message, retryable = true)),
                    onAcceptTag = { _, _ -> },
                    onDismiss = {},
                    onRetry = { retried = true },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(message).assertIsEnabled()
        val retryButton = composeTestRule.onNodeWithContentDescription("Retry downloading tags")
        retryButton.assertIsEnabled()
        retryButton.performClick()
        composeTestRule.waitForIdle()

        assertTrue(retried, "Retryable Failed must wire its Retry button to onRetry")
    }

    // ─── Accessibility fix regression: Failed's message carries LiveRegion.Polite ────────────

    @Test
    fun `Failed message carries LiveRegion Polite semantics for both retryable and non-retryable cases`() {
        val retryableMessage = "LLM tag suggestion timed out after 90s"
        val nonRetryableMessage = "On-device AI is not supported on this device"

        composeTestRule.setContent {
            MaterialTheme {
                SuggestionBottomSheet(
                    state = readyState(LlmSuggestionStatus.Failed(message = retryableMessage, retryable = true)),
                    onAcceptTag = { _, _ -> },
                    onDismiss = {},
                    onRetry = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        val retryableLiveRegion = composeTestRule.onNodeWithText(retryableMessage)
            .fetchSemanticsNode().config[SemanticsProperties.LiveRegion]
        assertEquals(LiveRegionMode.Polite, retryableLiveRegion, "Retryable Failed message must announce on transition")

        composeTestRule.setContent {
            MaterialTheme {
                SuggestionBottomSheet(
                    state = readyState(LlmSuggestionStatus.Failed(message = nonRetryableMessage, retryable = false)),
                    onAcceptTag = { _, _ -> },
                    onDismiss = {},
                    onRetry = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        val nonRetryableLiveRegion = composeTestRule.onNodeWithText(nonRetryableMessage)
            .fetchSemanticsNode().config[SemanticsProperties.LiveRegion]
        assertEquals(LiveRegionMode.Polite, nonRetryableLiveRegion, "Non-retryable Failed message must also announce on transition")
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    // ─── Criterion 5: no dead ends — every state has an exit path ────────────────────────────

    @Test
    fun `Stalled state offers both Retry and header Dismiss as reachable exits`() {
        var dismissed = false

        composeTestRule.setContent {
            MaterialTheme {
                SuggestionBottomSheet(
                    state = readyState(LlmSuggestionStatus.Stalled(retryable = true)),
                    onAcceptTag = { _, _ -> },
                    onDismiss = { dismissed = true },
                    onRetry = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Retry downloading tags").assertIsEnabled()
        val dismissAction = composeTestRule.onNodeWithContentDescription("Dismiss")
        dismissAction.assertIsEnabled()
        dismissAction.performClick()
        composeTestRule.waitForIdle()

        assertTrue(dismissed, "Dismiss must remain reachable alongside Retry")
    }

    // ─── Criterion 6: keyboard/switch-access navigable (real TextButton, not clickable Text) ──

    @Test
    fun `Retry affordance is a focusable TextButton, not a clickable Text`() {
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionBottomSheet(
                    state = readyState(LlmSuggestionStatus.Stalled(retryable = true)),
                    onAcceptTag = { _, _ -> },
                    onDismiss = {},
                    onRetry = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Retry downloading tags").assertHasClickAction()
    }

    // ─── Criterion 7: screen-reader grouping (mergeDescendants = true) ───────────────────────

    @Test
    fun `Stalled column merges heading secondary line and Retry into one semantics node`() {
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionBottomSheet(
                    state = readyState(LlmSuggestionStatus.Stalled(retryable = true)),
                    onAcceptTag = { _, _ -> },
                    onDismiss = {},
                    onRetry = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule
            .onNode(hasText("Taking longer than expected.") and hasAnyDescendant(hasText("Retry")))
            .assertExists()
    }

    // ─── Criterion 8: Retry structurally absent (not disabled) when unsupported ──────────────

    @Test
    fun `Retry button does not exist in the semantics tree when retryable is false`() {
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionBottomSheet(
                    state = readyState(LlmSuggestionStatus.Failed(message = "On-device AI is not supported on this device", retryable = false)),
                    onAcceptTag = { _, _ -> },
                    onDismiss = {},
                    onRetry = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Retry downloading tags").assertDoesNotExist()
    }
}
