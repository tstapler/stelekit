// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.screens.llm

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.llm.PendingLlmSuggestion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The project answer is information only: the action reads "Done" and the text is shown. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class ProjectAnswerReviewTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val answer = PendingLlmSuggestion.ProjectAnalysisAnswer(
        id = "a1", graphId = "g", sourceProviderId = "anthropic", proposedAtEpochMs = 0L,
        rationale = null, projectSlug = "proj-abc123", text = "Check the shelf zone.",
    )

    @Test
    fun showsTheAnswerAndADoneAction() {
        val accepted = mutableListOf<String>()
        composeTestRule.setContent {
            MaterialTheme {
                LlmSuggestionReviewScreen(
                    pending = listOf(answer), onAccept = { accepted += it }, onReject = {},
                    onAcceptAll = {}, onRejectAll = {}, onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Check the shelf zone.").assertExists()
        composeTestRule.onNodeWithText("Project answer").assertExists()
        composeTestRule.onNodeWithText("Done").performClick()

        assertEquals(listOf("a1"), accepted)
    }
}
