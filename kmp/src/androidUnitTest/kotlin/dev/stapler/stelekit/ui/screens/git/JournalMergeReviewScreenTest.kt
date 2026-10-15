// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.git.merge.JournalMergeProposal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Robolectric Compose tests for [JournalMergeReviewScreen] (phone-width stacked layout). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class JournalMergeReviewScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun proposal(warn: Boolean = false, markers: Boolean = false) = JournalMergeProposal(
        filePath = "journals/2024_01_01.md",
        localContent = "- local line",
        remoteContent = "- remote line",
        proposedMerge = "- merged line",
        confidenceWarning = warn,
        hasConflictMarkers = markers,
        backupPath = "/tmp/backup",
    )

    private class Calls {
        val accepted = mutableListOf<String>()
        var manual = 0
        var dismissed = 0
    }

    private fun show(p: JournalMergeProposal, c: Calls = Calls()): Calls {
        composeTestRule.setContent {
            MaterialTheme {
                JournalMergeReviewScreen(
                    proposal = p,
                    onAccept = { c.accepted += it },
                    onFallbackToManual = { c.manual++ },
                    onDismiss = { c.dismissed++ },
                )
            }
        }
        return c
    }

    @Test
    fun review_should_showThreePanels_and_acceptMergedContent() {
        val c = show(proposal())
        composeTestRule.onNodeWithText("Review Merged Journal").assertExists()
        composeTestRule.onNodeWithText("LOCAL (read-only)").assertExists()
        composeTestRule.onNodeWithText("MERGED RESULT").assertExists()
        composeTestRule.onNodeWithText("REMOTE (read-only)").assertExists()
        composeTestRule.onNodeWithText("- merged line").assertExists()
        composeTestRule.onNodeWithText("Accept").performClick()
        assertEquals(listOf("- merged line"), c.accepted)
    }

    @Test
    fun review_should_fireManualFallback() {
        val c = show(proposal())
        composeTestRule.onNodeWithText("Manual resolve").performClick()
        assertEquals(1, c.manual)
    }

    @Test
    fun review_should_hideWarnings_when_flagsFalse() {
        show(proposal())
        assertTrue(composeTestRule.onAllNodesWithText("shorter than expected", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("could not be merged", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun review_should_showWarnings_when_flagsTrue() {
        show(proposal(warn = true, markers = true))
        composeTestRule.onNodeWithText("shorter than expected", substring = true).assertExists()
        composeTestRule.onNodeWithText("could not be merged", substring = true).assertExists()
    }

    @Test
    fun review_should_confirmAbandon_then_dismiss() {
        val c = show(proposal())
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Abandon merge review?").assertExists()
        composeTestRule.onNodeWithText("Continue editing").performClick()
        assertEquals(0, c.dismissed)
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Discard").performClick()
        assertEquals(1, c.dismissed)
    }
}
