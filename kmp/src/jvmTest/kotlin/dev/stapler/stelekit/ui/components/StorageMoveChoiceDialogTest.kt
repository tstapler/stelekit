// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.StorageLocation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * Story 3.4.1: no test task is named in plan.md for this dialog (validation.md's REQ-5/AC18-21
 * rows call this out as a plan gap), but the dialog itself needs the same basic render/dismiss
 * coverage every other new dialog in this epic has.
 */
class StorageMoveChoiceDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val source = StorageLocation.SafFolder(graphId = "g1", treeUri = "content://tree/primary%3ADocuments")
    private val destination = StorageLocation.AppOwned(graphId = "g1")

    /** AC18/AC19: both options render, each with its consequence text. */
    @Test
    fun storageMoveChoiceDialog_should_RenderBothOptionsWithConsequenceText_When_Rendered() {
        composeTestRule.setContent {
            StorageMoveChoiceDialog(
                graphName = "My Notes",
                source = source,
                destination = destination,
                onRelocateChoose = {},
                onLinkChoose = {},
                onDismissRequest = {},
            )
        }

        composeTestRule.onNodeWithText("Relocate").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Move and stop using the old location. The old copy stays until you confirm it's safe to remove.",
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Link").assertIsDisplayed()
        composeTestRule.onNodeWithText("Keep both copies in sync. Nothing is ever removed.").assertIsDisplayed()
    }

    /** Story 4.2.1's AC (rendered here per REQ-5): Link is hidden, not disabled, for a plain non-git Android graph. */
    @Test
    fun storageMoveChoiceDialog_should_HideLinkCard_When_PlainNonGitAndroidGraph() {
        composeTestRule.setContent {
            StorageMoveChoiceDialog(
                graphName = "My Notes",
                source = source,
                destination = destination,
                isLinkAvailable = false,
                onRelocateChoose = {},
                onLinkChoose = {},
                onDismissRequest = {},
            )
        }

        composeTestRule.onNodeWithText("Link").assertDoesNotExist()
        composeTestRule.onNodeWithText("Continuous sync isn't available yet for graphs without git.")
            .assertIsDisplayed()
    }

    /** AC21: Cancel returns to Surface 4 with no operation started. */
    @Test
    fun storageMoveChoiceDialog_should_StartNoOperation_When_Cancelled() {
        var dismissed = false
        var relocateChosen = false
        var linkChosen = false
        composeTestRule.setContent {
            StorageMoveChoiceDialog(
                graphName = "My Notes",
                source = source,
                destination = destination,
                onRelocateChoose = { relocateChosen = true },
                onLinkChoose = { linkChosen = true },
                onDismissRequest = { dismissed = true },
            )
        }

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertTrue(dismissed)
        assertFalse(relocateChosen)
        assertFalse(linkChosen)
    }
}
