// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.llm

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import dev.stapler.stelekit.llm.PendingLlmSuggestion
import dev.stapler.stelekit.llm.ProposedBlock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Robolectric Compose tests for [LlmSuggestionReviewScreen] across suggestion types and callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class LlmSuggestionReviewScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun edit(id: String = "e1", provider: String = "llm") = PendingLlmSuggestion.BlockEdit(
        id = id, graphId = "g", sourceProviderId = provider, proposedAtEpochMs = 0L,
        rationale = "Tighter wording", pageUuid = "p", blockUuid = "b",
        currentContentSnapshot = "old text", proposedContent = "new text",
    )

    private fun tag() = PendingLlmSuggestion.TagChange(
        id = "t1", graphId = "g", sourceProviderId = "llm", proposedAtEpochMs = 0L,
        rationale = null, pageUuid = "p", blockUuid = "b", currentContentSnapshot = "content",
        addedTerms = listOf("Alpha"), removedTerms = listOf("Beta"),
    )

    private fun newPage() = PendingLlmSuggestion.NewPage(
        id = "n1", graphId = "g", sourceProviderId = "llm", proposedAtEpochMs = 0L,
        rationale = null, proposedTitle = "Brand New Page",
        proposedBlocks = listOf(ProposedBlock("second", 1, 1), ProposedBlock("first", 0, 0)),
    )

    private fun unlinked() = PendingLlmSuggestion.UnlinkedReference(
        id = "u1", graphId = "g", sourceProviderId = "aho-corasick-matcher", proposedAtEpochMs = 0L,
        rationale = null, pageUuid = "p", blockUuid = "b", targetPageName = "Kotlin",
        matchStart = 5, matchEnd = 11, currentContentSnapshot = "I use Kotlin daily",
    )

    private class Calls {
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        var acceptAll = 0
        var rejectAll = 0
        var dismissed = 0
    }

    private fun show(pending: List<PendingLlmSuggestion>, calls: Calls = Calls()): Calls {
        composeTestRule.setContent {
            MaterialTheme {
                LlmSuggestionReviewScreen(
                    pending = pending,
                    onAccept = { calls.accepted += it },
                    onReject = { calls.rejected += it },
                    onAcceptAll = { calls.acceptAll++ },
                    onRejectAll = { calls.rejectAll++ },
                    onDismiss = { calls.dismissed++ },
                )
            }
        }
        return calls
    }

    @Test
    fun review_should_showEditBadgeAndDiff_when_blockEdit() {
        show(listOf(edit()))
        composeTestRule.onNodeWithText("Review LLM Suggestions (1/1)").assertExists()
        composeTestRule.onNodeWithText("Edit").assertExists()
        composeTestRule.onNodeWithText("Tighter wording").assertExists()
        composeTestRule.onNodeWithText("Before").assertExists()
        composeTestRule.onNodeWithText("After").assertExists()
        composeTestRule.onNodeWithText("Apply").assertExists()
    }

    @Test
    fun review_should_showTagChangeBadge_when_tagChange() {
        show(listOf(tag()))
        composeTestRule.onNodeWithText("Tag Change").assertExists()
        composeTestRule.onNodeWithText("Apply").assertExists()
    }

    @Test
    fun review_should_showNewNotePreview_when_newPage() {
        show(listOf(newPage()))
        composeTestRule.onNodeWithText("New Note").assertExists()
        composeTestRule.onNodeWithText("Brand New Page").assertExists()
        composeTestRule.onNodeWithText("Create").assertExists()
    }

    @Test
    fun review_should_showLinkLabels_when_unlinkedReference() {
        show(listOf(unlinked()))
        assertEquals(2, composeTestRule.onAllNodesWithText("Link").fetchSemanticsNodes().size)
    }

    @Test
    fun review_should_showMcpChip_when_providerIsMcp() {
        show(listOf(edit(provider = "mcp:claude-desktop:42")))
        composeTestRule.onNodeWithText("MCP: claude-desktop").assertExists()
    }

    @Test
    fun review_should_hideMcpChip_when_providerIsNotMcp() {
        show(listOf(edit()))
        assertTrue(composeTestRule.onAllNodesWithText("MCP:", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun review_should_fireAcceptReject_withCurrentId() {
        val calls = show(listOf(edit("e1")))
        composeTestRule.onNodeWithText("Apply").performClick()
        composeTestRule.onNodeWithText("Discard").performClick()
        assertEquals(listOf("e1"), calls.accepted)
        assertEquals(listOf("e1"), calls.rejected)
    }

    @Test
    fun review_should_fireDismiss_when_closeClicked() {
        val calls = show(listOf(edit()))
        composeTestRule.onNodeWithText("Close").performClick()
        assertEquals(1, calls.dismissed)
    }

    @Test
    fun review_should_navigateBetweenItems_and_actOnCurrent() {
        val calls = show(listOf(edit("e1"), tag(), newPage()))
        composeTestRule.onNodeWithText("1 of 3").assertExists()
        composeTestRule.onNodeWithContentDescription("Previous suggestion").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Next suggestion").performClick()
        composeTestRule.onNodeWithText("2 of 3").assertExists()
        composeTestRule.onNodeWithText("Tag Change").assertExists()
        composeTestRule.onNodeWithContentDescription("Next suggestion").performClick()
        composeTestRule.onNodeWithContentDescription("Next suggestion").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Create").performClick()
        assertEquals(listOf("n1"), calls.accepted)
        composeTestRule.onNodeWithContentDescription("Previous suggestion").assertIsEnabled().performClick()
        composeTestRule.onNodeWithText("2 of 3").assertExists()
    }

    @Test
    fun review_should_hideBulkActions_until_navigatedPastFirst() {
        show(listOf(edit("e1"), tag()))
        assertTrue(composeTestRule.onAllNodesWithText("Discard all").fetchSemanticsNodes().isEmpty())
        composeTestRule.onNodeWithContentDescription("Next suggestion").performClick()
        composeTestRule.onNodeWithText("Discard all").assertExists()
    }

    @Test
    fun review_should_requireSecondTap_for_applyAll() {
        val calls = show(listOf(edit("e1"), tag()))
        composeTestRule.onNodeWithContentDescription("Next suggestion").performClick()
        composeTestRule.onNodeWithText("Apply all").performClick()
        assertEquals(0, calls.acceptAll)
        composeTestRule.onNodeWithText("Tap again to apply all").performClick()
        assertEquals(1, calls.acceptAll)
        composeTestRule.onNodeWithText("Apply all").assertExists()
    }

    @Test
    fun review_should_fireRejectAll_withSingleTap() {
        val calls = show(listOf(edit("e1"), tag()))
        composeTestRule.onNodeWithContentDescription("Next suggestion").performClick()
        composeTestRule.onNodeWithText("Discard all").performClick()
        assertEquals(1, calls.rejectAll)
    }

    @Test
    fun review_should_dismiss_when_pendingIsEmpty() {
        val calls = show(emptyList())
        composeTestRule.waitForIdle()
        assertEquals(1, calls.dismissed)
    }

    @Test
    fun review_should_clampIndex_when_listShrinks() {
        val list = mutableStateOf(listOf(edit("e1"), tag(), newPage()))
        composeTestRule.setContent {
            MaterialTheme {
                LlmSuggestionReviewScreen(
                    pending = list.value, onAccept = {}, onReject = {}, onAcceptAll = {},
                    onRejectAll = {}, onDismiss = {},
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Next suggestion").performClick()
        composeTestRule.onNodeWithContentDescription("Next suggestion").performClick()
        composeTestRule.onNodeWithText("3 of 3").assertExists()
        list.value = listOf(edit("e1"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Review LLM Suggestions (1/1)").assertExists()
    }
}
