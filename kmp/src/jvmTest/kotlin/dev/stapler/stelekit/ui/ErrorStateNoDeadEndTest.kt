// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.tags.TagSuggestionState
import dev.stapler.stelekit.ui.components.DiskConflictDialog
import dev.stapler.stelekit.ui.components.VoiceCaptureButton
import dev.stapler.stelekit.ui.components.tags.SuggestionBottomSheet
import dev.stapler.stelekit.voice.PipelineStage
import dev.stapler.stelekit.voice.VoiceCaptureState
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * design/ux.md acceptance criteria 14 & 15: every error/edge-case surface must show a specific
 * message + a specific, reachable action -- never a generic "Something went wrong" with no way
 * forward. Walks the four representative surfaces named in
 * `project_plans/rich-editing-experience/implementation/validation.md`'s UX Acceptance Tests
 * table (row 14): LLM-suggestion failure, disk-conflict-pending resolution actions,
 * voice-capture Error, and voice-capture truncation warning.
 *
 * Each test asserts both halves of criterion 14 (specific message, concrete action present) and
 * criterion 15 (the action is not just rendered but actually reachable -- clicking it fires the
 * expected callback / drives the state machine forward, not a dead end).
 */
class ErrorStateNoDeadEndTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val genericDeadEndMessage = "Something went wrong"

    // ─── LLM-suggestion failure (SuggestionBottomSheet / TagSuggestionState.Ready.llmError) ────

    @Test
    fun `LLM-suggestion failure shows specific message and a reachable dismiss action`() {
        var dismissed = false
        val errorMessage = "LLM tag suggestion timed out after 5s"

        composeTestRule.setContent {
            MaterialTheme {
                SuggestionBottomSheet(
                    state = TagSuggestionState.Ready(
                        blockUuid = "block-1",
                        localSuggestions = emptyList(),
                        llmSuggestions = emptyList(),
                        llmError = errorMessage,
                    ),
                    onAcceptTag = { _, _ -> },
                    onDismiss = { dismissed = true },
                )
            }
        }
        composeTestRule.waitForIdle()

        // The error message is surfaced both inline in TagChipRow and below it in
        // SuggestionBottomSheet (TagChipRow.kt / SuggestionBottomSheet.kt), so match the first.
        composeTestRule.onAllNodesWithText(errorMessage).onFirst().assertIsEnabled()
        assertFalse(errorMessage.contains(genericDeadEndMessage), "Sanity: fixture message must not be the generic dead-end text")

        val dismissAction = composeTestRule.onNodeWithContentDescription("Dismiss")
        dismissAction.assertIsEnabled()
        dismissAction.performClick()
        composeTestRule.waitForIdle()

        assertTrue(dismissed, "Dismiss action on an LLM-suggestion failure must be reachable, not a dead end")
    }

    // ─── Disk-conflict-pending resolution actions (DiskConflictDialog) ───────────────────────────

    @Test
    fun `disk-conflict-pending state shows specific message and reachable resolution actions`() {
        var keptLocal = false
        var usedDisk = false
        var savedAsNew = false
        var manuallyResolved = false

        val conflict = dev.stapler.stelekit.ui.DiskConflict(
            pageUuid = "page-1",
            pageName = "Groceries",
            filePath = "/graph/pages/groceries.md",
            editingBlockUuid = "block-1",
            localContent = "- Buy milk (edited)",
            diskContent = "- Buy milk and eggs",
        )

        composeTestRule.setContent {
            MaterialTheme {
                DiskConflictDialog(
                    conflict = conflict,
                    onKeepLocal = { keptLocal = true },
                    onUseDisk = { usedDisk = true },
                    onSaveAsNew = { savedAsNew = true },
                    onManualResolve = { manuallyResolved = true },
                    onViewFull = { },
                )
            }
        }
        composeTestRule.waitForIdle()

        // Specific message naming the actual page, not a generic dead-end string.
        composeTestRule.onNodeWithText("Page modified on disk").assertIsEnabled()
        composeTestRule.onNodeWithText(
            "\"${conflict.pageName}\" was changed externally while you were editing.",
        ).assertIsEnabled()

        // Concrete, reachable resolution actions -- verify one actually fires end to end.
        val useDiskButton = composeTestRule.onNodeWithText("Use disk version")
        useDiskButton.assertIsEnabled()
        useDiskButton.performClick()
        composeTestRule.waitForIdle()

        assertTrue(usedDisk, "'Use disk version' action must be reachable, not a dead end")
        assertFalse(keptLocal || savedAsNew || manuallyResolved, "Only the clicked action should have fired")
    }

    // ─── Voice-capture Error ──────────────────────────────────────────────────────────────────────

    @Test
    fun `voice-capture Error shows specific message and a reachable dismiss action`() {
        var dismissed = false
        val errorMessage = "Network error — check your connection"

        composeTestRule.setContent {
            MaterialTheme {
                VoiceCaptureButton(
                    state = VoiceCaptureState.Error(PipelineStage.TRANSCRIBING, errorMessage),
                    onTap = {},
                    onDismissError = { dismissed = true },
                    onAutoReset = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(errorMessage).assertIsEnabled()
        assertFalse(errorMessage.contains(genericDeadEndMessage))

        val dismissAction = composeTestRule.onNodeWithContentDescription("Error — tap to dismiss")
        dismissAction.assertIsEnabled()
        dismissAction.performClick()
        composeTestRule.waitForIdle()

        assertTrue(dismissed, "Voice-capture Error's dismiss action must be reachable, not a dead end")
    }

    // ─── Voice-capture truncation warning ─────────────────────────────────────────────────────────

    @Test
    fun `voice-capture truncation warning shows specific message and a reachable dismiss action`() {
        var dismissed = false

        composeTestRule.setContent {
            MaterialTheme {
                VoiceCaptureButton(
                    state = VoiceCaptureState.Done(insertedText = "- Test note", isLikelyTruncated = true),
                    onTap = {},
                    onDismissError = {},
                    onAutoReset = { dismissed = true },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Note may be incomplete").assertIsEnabled()

        val dismissAction = composeTestRule.onNodeWithContentDescription("Note saved — may be incomplete. Tap to dismiss.")
        dismissAction.assertIsEnabled()
        dismissAction.performClick()
        composeTestRule.waitForIdle()

        assertTrue(dismissed, "Truncation warning's dismiss action must be reachable, not a dead end -- silently accepting a partial transcript is the failure mode this guards against")
    }
}
