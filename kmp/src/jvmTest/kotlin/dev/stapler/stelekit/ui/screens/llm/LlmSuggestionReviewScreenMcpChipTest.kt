// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.screens.llm

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.llm.PendingLlmSuggestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import org.junit.Rule

class LlmSuggestionReviewScreenMcpChipTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun blockEdit(sourceProviderId: String): PendingLlmSuggestion.BlockEdit =
        PendingLlmSuggestion.BlockEdit(
            id = "id-$sourceProviderId",
            graphId = "g1",
            sourceProviderId = sourceProviderId,
            proposedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            rationale = null,
            pageUuid = "p1",
            blockUuid = "b1",
            currentContentSnapshot = "before",
            proposedContent = "after",
        )

    @Test
    fun `parseMcpClientName extracts the client name only for mcp-sourced ids`() {
        assertEquals("claude-desktop", parseMcpClientName("mcp:claude-desktop:abc123"))
        assertNull(parseMcpClientName("aho-corasick-matcher"))
        assertEquals("", parseMcpClientName("mcp:"), "no client-name segment present — empty, not null, since the prefix matched")
    }

    @Test
    fun `only the mcp-sourced suggestion shows the MCP provenance chip, the matcher-sourced one shows none`() {
        val mcpSuggestion = blockEdit("mcp:claude-desktop:abc123")
        val matcherSuggestion = blockEdit("aho-corasick-matcher")

        composeTestRule.setContent {
            MaterialTheme {
                LlmSuggestionReviewScreen(
                    pending = listOf(mcpSuggestion, matcherSuggestion),
                    onAccept = {},
                    onReject = {},
                    onAcceptAll = {},
                    onRejectAll = {},
                    onDismiss = {},
                )
            }
        }

        // First item (mcp-sourced): chip present, with the screen-reader-friendly description.
        composeTestRule.onNodeWithText("MCP: claude-desktop").assertExists()
        composeTestRule.onNodeWithContentDescription("Proposed by MCP client claude-desktop").assertExists()

        // Advance to the matcher-sourced item: no chip.
        composeTestRule.onNodeWithContentDescription("Next suggestion").performClick()
        composeTestRule.onNodeWithText("MCP: claude-desktop").assertDoesNotExist()
    }
}
