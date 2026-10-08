// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.tags.LlmSuggestionStatus
import dev.stapler.stelekit.tags.TagSuggestion
import dev.stapler.stelekit.tags.TagSuggestionState
import dev.stapler.stelekit.ui.components.tags.SuggestionBottomSheet
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Chip rendering, "Link all", accepted-chip hiding, and empty-state text of [SuggestionBottomSheet]. */
class SuggestionBottomSheetChipsUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val emptyText = "No new tag suggestions for this block."

    private fun local(term: String) =
        TagSuggestion(term = term, confidence = 1.0f, source = TagSuggestion.Source.LOCAL, autoApplied = true)

    private fun llm(term: String) =
        TagSuggestion(term = term, confidence = 0.8f, source = TagSuggestion.Source.LLM)

    private fun ready(
        local: List<TagSuggestion> = emptyList(),
        llm: List<TagSuggestion> = emptyList(),
        status: LlmSuggestionStatus = LlmSuggestionStatus.Resolved,
    ) = TagSuggestionState.Ready("block-1", local, llm, status)

    private fun show(
        state: TagSuggestionState,
        onAcceptTag: (String, String) -> Unit = { _, _ -> },
        onAcceptAll: ((String, List<String>) -> Unit)? = null,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionBottomSheet(
                    state = state,
                    onAcceptTag = onAcceptTag,
                    onAcceptAll = onAcceptAll,
                    onDismiss = {},
                    onRetry = {},
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `Ready with only an autoApplied local match renders a tappable chip`() {
        show(ready(local = listOf(local("Kotlin"))))

        composeTestRule.onNodeWithText("Kotlin").assertHasClickAction()
    }

    @Test
    fun `Link all is absent with a single suggestion`() {
        show(ready(local = listOf(local("Kotlin"))), onAcceptAll = { _, _ -> })

        composeTestRule.onNodeWithText("Link all 1").assertDoesNotExist()
        composeTestRule.onNodeWithText("Link all", substring = true).assertDoesNotExist()
    }

    @Test
    fun `Link all is absent when no onAcceptAll handler is supplied`() {
        show(ready(local = listOf(local("Kotlin"), local("Compose"))))

        composeTestRule.onNodeWithText("Link all", substring = true).assertDoesNotExist()
    }

    @Test
    fun `Link all counts deduped local plus llm suggestions and fires once with the terms`() {
        val calls = mutableListOf<Pair<String, List<String>>>()
        show(
            ready(local = listOf(local("Kotlin")), llm = listOf(llm("kotlin"), llm("Compose"))),
            onAcceptAll = { uuid, terms -> calls += uuid to terms },
        )

        composeTestRule.onNodeWithText("Link all 2").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("block-1" to listOf("Kotlin", "Compose")), calls)
    }

    @Test
    fun `tapping a chip reports the term and hides it`() {
        val accepted = mutableListOf<String>()
        show(
            ready(local = listOf(local("Kotlin")), llm = listOf(llm("Compose"))),
            onAcceptTag = { _, term -> accepted += term },
        )

        composeTestRule.onNodeWithText("Kotlin").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("Kotlin"), accepted)
        composeTestRule.onNodeWithText("Kotlin").assertDoesNotExist()
        composeTestRule.onNodeWithText("Compose").assertHasClickAction()
    }

    @Test
    fun `accepting a chip leaves one suggestion so Link all disappears`() {
        show(
            ready(llm = listOf(llm("Kotlin"), llm("Compose"))),
            onAcceptAll = { _, _ -> },
        )
        composeTestRule.onNodeWithText("Link all 2").assertHasClickAction()

        composeTestRule.onNodeWithText("Kotlin").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Link all", substring = true).assertDoesNotExist()
    }

    @Test
    fun `empty-state text shows for Resolved with no suggestions`() {
        show(ready(status = LlmSuggestionStatus.Resolved))

        composeTestRule.onNodeWithText(emptyText).assertExists()
    }

    @Test
    fun `empty-state text is absent for Pending`() {
        show(ready(status = LlmSuggestionStatus.Pending()))

        composeTestRule.onNodeWithText(emptyText).assertDoesNotExist()
    }

    @Test
    fun `empty-state text is absent for NotStarted`() {
        show(ready(status = LlmSuggestionStatus.NotStarted))

        composeTestRule.onNodeWithText(emptyText).assertDoesNotExist()
    }

    @Test
    fun `empty-state text is absent for Failed`() {
        show(ready(status = LlmSuggestionStatus.Failed(message = "boom", retryable = false)))

        composeTestRule.onNodeWithText(emptyText).assertDoesNotExist()
    }
}
