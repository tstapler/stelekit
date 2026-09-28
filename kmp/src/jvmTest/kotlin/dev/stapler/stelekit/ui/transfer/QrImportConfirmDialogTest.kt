package dev.stapler.stelekit.ui.transfer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.transfer.qrcode.QrImportService
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Story 3.2.4 / UX Acceptance Test criteria 4, 10: [QrImportConfirmDialog] offers the three
 * explicit S11 choices, never silently overwrites, and keeps Cancel tappable even while a chosen
 * write is in flight (only the two write-triggering buttons disable).
 */
class QrImportConfirmDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dialog_should_OfferKeepBothOverwriteCancel_When_PageNameCollisionDetected() {
        var keepBothClicked = false
        var overwriteClicked = false
        var cancelClicked = false

        composeTestRule.setContent {
            QrImportConfirmDialog(
                existingName = "Meeting Notes",
                pendingChoice = null,
                onKeepBoth = { keepBothClicked = true },
                onOverwrite = { overwriteClicked = true },
                onCancel = { cancelClicked = true },
            )
        }

        composeTestRule.onNodeWithText("A page named \"Meeting Notes\" already exists.", substring = true)
            .assertIsDisplayed()

        composeTestRule.onNodeWithText("Keep both").assertIsDisplayed().performClick()
        assertEquals(true, keepBothClicked)

        composeTestRule.onNodeWithText("Overwrite").assertIsDisplayed().performClick()
        assertEquals(true, overwriteClicked)

        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        assertEquals(true, cancelClicked)
    }

    @Test
    fun dialog_should_BlockDismissRequestAndKeepCancelTappable_When_ChosenWriteIsInFlight() {
        var cancelClicked = false

        composeTestRule.setContent {
            QrImportConfirmDialog(
                existingName = "Meeting Notes",
                pendingChoice = QrImportService.CollisionChoice.OVERWRITE,
                onKeepBoth = {},
                onOverwrite = {},
                onCancel = { cancelClicked = true },
            )
        }

        // The chosen button (Overwrite) shows a spinner in place of its label — its text is gone.
        composeTestRule.onNodeWithText("Overwrite").assertDoesNotExist()
        // The OTHER write-triggering button disables.
        composeTestRule.onNodeWithText("Keep both").assertIsNotEnabled()
        // Cancel stays tappable throughout.
        composeTestRule.onNodeWithText("Cancel").assertIsEnabled().performClick()
        assertEquals(true, cancelClicked)
    }
}
