// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.db.StorageMoveUiState
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.GraphId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule

/** Tasks 3.4.3b, 3.4.3c, 3.4.3d: validation.md AC27-33, AC45-48. */
class StorageMoveProgressDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setState(state: StorageMoveUiState, onCancel: () -> Unit = {}, onRetry: () -> Unit = {}) {
        composeTestRule.setContent {
            StorageMoveProgressDialog(
                graphName = "My Notes",
                state = state,
                onCancel = onCancel,
                onRetry = onRetry,
                onSummaryAcknowledge = {},
                onReopenFailedAcknowledge = {},
            )
        }
    }

    /** AC27: Copying always shows a determinate "N of M" count, never a bare indeterminate spinner. */
    @Test
    fun progressDialog_should_ShowDeterminateCount_When_CopyingStateEmitted() {
        setState(StorageMoveUiState.Copying(count = 4200, total = 8030))

        composeTestRule.onNodeWithText("Copying files… 4200 of 8030").assertIsDisplayed()
    }

    /** AC28: Quiescing and Verifying show descriptive text naming the current step. */
    @Test
    fun progressDialog_should_ShowDescriptiveLabel_When_QuiescingOrVerifyingStateEmitted() {
        setState(StorageMoveUiState.Quiescing)
        composeTestRule.onNodeWithText("Waiting for in-flight changes to finish…").assertIsDisplayed()

        setState(StorageMoveUiState.Verifying)
        composeTestRule.onNodeWithText("Verifying copied files…").assertIsDisplayed()
    }

    /** AC31: only Retry and Cancel are ever offered on Failed — no "delete anyway"/"use it anyway." */
    @Test
    fun progressDialog_should_ShowOnlyRetryAndCancel_When_FailedStateEmitted() {
        setState(StorageMoveUiState.Failed(DomainError.StorageError.VerificationFailed("pages/foo.md", "hash mismatch")))

        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeTestRule.onAllNodes(hasClickAction()).assertCountEquals(2)
    }

    /** AC32: the "not touched or deleted" reassurance renders for every StorageError subtype. */
    @Test
    fun progressDialog_should_ShowUntouchedReassurance_When_AnyStorageErrorSubtypeFailed() {
        val subtypes = listOf(
            DomainError.StorageError.VerificationFailed("pages/foo.md", "hash mismatch"),
            DomainError.StorageError.DestinationNotWritable("/dest"),
            DomainError.StorageError.PartialCopyDetected("/dest"),
            DomainError.StorageError.InsufficientSpace(requiredBytes = 100, availableBytes = 10),
            DomainError.StorageError.QuiesceTimedOut(waitedMs = 5000),
        )
        for (reason in subtypes) {
            setState(StorageMoveUiState.Failed(reason))
            composeTestRule.onNodeWithText("Your original files were not touched or deleted.")
                .assertIsDisplayed()
        }
    }

    /** AC33: Retry restarts the coordinator; Cancel returns with nothing changed — both recoverable. */
    @Test
    fun progressDialog_should_LeadToRecoverableState_When_RetryOrCancelTapped() {
        var retried = false
        setState(
            StorageMoveUiState.Failed(DomainError.StorageError.VerificationFailed("pages/foo.md", "hash mismatch")),
            onRetry = { retried = true },
        )
        composeTestRule.onNodeWithText("Retry").performClick()
        assertTrue(retried)

        var cancelled = false
        setState(
            StorageMoveUiState.Failed(DomainError.StorageError.VerificationFailed("pages/foo.md", "hash mismatch")),
            onCancel = { cancelled = true },
        )
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertTrue(cancelled)
    }

    /** AC48/Task 3.4.3d: Cancel is present and enabled during Verifying — the earlier-disabled regression. */
    @Test
    fun progressDialog_should_ShowCancelPresentAndEnabled_When_VerifyingStateEmitted() {
        var cancelled = false
        setState(StorageMoveUiState.Verifying, onCancel = { cancelled = true })

        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertTrue(cancelled, "Verifying's Cancel must invoke the identical cancel callback Copying/Quiescing use")
    }

    /** AC45/Task 3.4.3c: ReopenFailed never renders a "Retry" button. */
    @Test
    fun progressDialog_should_ShowNoRetryButton_When_ReopenFailedStateEmitted() {
        setState(
            StorageMoveUiState.ReopenFailed(
                graphId = GraphId("g1"),
                cause = DomainError.StorageError.ReopenFailed("g1"),
            ),
        )

        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    /** AC46/Task 3.4.3c: ReopenFailed never shows Failed's verbatim "untouched" reassurance. */
    @Test
    fun progressDialog_should_ShowNoUntouchedReassurance_When_ReopenFailedStateEmitted() {
        setState(
            StorageMoveUiState.ReopenFailed(
                graphId = GraphId("g1"),
                cause = DomainError.StorageError.ReopenFailed("g1"),
            ),
        )

        composeTestRule.onNodeWithText("Your original files were not touched or deleted.").assertDoesNotExist()
        // The narrower claim this screen CAN back up — the old location specifically — is still present.
        composeTestRule.onNodeWithText("old location was never removed", substring = true).assertIsDisplayed()
    }

    /** AC45/AC47: only "OK" is offered on ReopenFailed, and it's reachable/clickable. */
    @Test
    fun progressDialog_should_ShowOnlyOkButton_When_ReopenFailedStateEmitted() {
        var acknowledged = false
        composeTestRule.setContent {
            StorageMoveProgressDialog(
                graphName = "My Notes",
                state = StorageMoveUiState.ReopenFailed(
                    graphId = GraphId("g1"),
                    cause = DomainError.StorageError.ReopenFailed("g1"),
                ),
                onCancel = {},
                onRetry = {},
                onSummaryAcknowledge = {},
                onReopenFailedAcknowledge = { acknowledged = true },
            )
        }

        composeTestRule.onAllNodes(hasClickAction()).assertCountEquals(1)
        composeTestRule.onNodeWithText("OK").performClick()
        assertTrue(acknowledged)
    }

    /** AC30: dialog content renders no clickable outside-dismiss target during Copying — Cancel is the only escape hatch. */
    @Test
    fun progressDialog_should_OnlyOfferExplicitCancel_When_MoveInProgress() {
        var cancelled = false
        setState(StorageMoveUiState.Copying(count = 10, total = 100), onCancel = { cancelled = true })

        composeTestRule.onAllNodes(hasClickAction()).assertCountEquals(1)
        assertFalse(cancelled)
    }
}
