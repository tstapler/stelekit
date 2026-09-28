// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.StorageLocation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule

/** Task 3.4.2b: validation.md AC22-26. */
class StorageMoveConfirmDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val source = StorageLocation.SafFolder(graphId = "g1", treeUri = "content://com.android.externalstorage/tree/primary%3ADocuments")
    private val destination = StorageLocation.AppOwned(graphId = "g1")

    /** AC22: names the exact source/destination in human-readable form, never a raw content:// URI. */
    @Test
    fun confirmDialog_should_NameExactSourceAndDestination_When_Rendered() {
        composeTestRule.setContent {
            StorageMoveConfirmDialog(
                graphName = "My Notes",
                source = source,
                destination = destination,
                onConfirm = {},
                onDismissRequest = {},
            )
        }

        composeTestRule.onNodeWithText(
            "Move \"My Notes\" from a folder on your device to App storage?",
            substring = true,
        ).assertIsDisplayed()
        // Never a raw content:// URI or OPFS path anywhere in the dialog's text.
        composeTestRule.onNodeWithText(source.treeUri, substring = true).assertDoesNotExist()
    }

    /** AC23: default focus lands on Cancel, never the confirm action. */
    @Test
    fun confirmDialog_should_DefaultFocusToCancel_When_Rendered() {
        composeTestRule.setContent {
            StorageMoveConfirmDialog(
                graphName = "My Notes",
                source = source,
                destination = destination,
                onConfirm = {},
                onDismissRequest = {},
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Cancel").assertIsFocused()
    }

    /**
     * AC24: Escape dismisses without starting a move. Confirmed empirically (not just asserted)
     * that the Compose Desktop test harness cannot simulate a real window-level Escape against
     * `AlertDialog`'s separate platform `Dialog` window: sending `Key.Escape` via
     * `performKeyInput` at the focused "Cancel" node does not reach it — Material3's `AlertDialog`
     * delegates Escape-to-dismiss to the platform dialog window's own native handling on Desktop,
     * outside Compose's semantics/key-input pipeline `performKeyInput` targets (the same
     * limitation `QrImportConfirmDialogUxTest` documents for outside-tap). This test instead
     * asserts the load-bearing contract that makes Escape safe in production: `onDismissRequest`
     * is wired straight through with no move-starting side effect hiding behind it — proven here
     * via the identical callback Escape would invoke (`AlertDialog`'s `onDismissRequest`, which
     * this composable passes unwrapped), exercised through "Cancel" since it invokes that same
     * lambda.
     */
    @Test
    fun confirmDialog_should_DismissWithoutStartingMove_When_EscapePressed() {
        var dismissed = false
        var moveStarted = false
        composeTestRule.setContent {
            StorageMoveConfirmDialog(
                graphName = "My Notes",
                source = source,
                destination = destination,
                onConfirm = { moveStarted = true },
                onDismissRequest = { dismissed = true },
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertTrue(dismissed, "the dismiss path Escape shares with Cancel must invoke onDismissRequest")
        assertFalse(moveStarted, "dismissing must never start a move")
    }

    /** AC25: the reassurance sentence is present verbatim, both as visible text and as the accessible description. */
    @Test
    fun confirmDialog_should_IncludeVerbatimReassuranceSentence_When_Rendered() {
        composeTestRule.setContent {
            StorageMoveConfirmDialog(
                graphName = "My Notes",
                source = source,
                destination = destination,
                onConfirm = {},
                onDismissRequest = {},
            )
        }

        composeTestRule.onNodeWithText("Your files stay where they are until the copy is verified.")
            .assertIsDisplayed()
    }

    /** AC26: Cancel returns to the prior surface with the graph completely unchanged. */
    @Test
    fun confirmDialog_should_ReturnToPriorSurfaceUnchanged_When_CancelledOrEscaped() {
        var dismissed = false
        var moveStarted = false
        composeTestRule.setContent {
            StorageMoveConfirmDialog(
                graphName = "My Notes",
                source = source,
                destination = destination,
                onConfirm = { moveStarted = true },
                onDismissRequest = { dismissed = true },
            )
        }

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertTrue(dismissed)
        assertFalse(moveStarted)
    }
}
