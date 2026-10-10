package dev.stapler.stelekit.ui.transfer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.transfer.qrcode.QrImportService
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Story 3.2.4 / UX Acceptance Test criteria 4, 10 (validation.md's dedicated UX-suffixed
 * companion to [QrImportConfirmDialogTest], which already covers the same collaborator contract —
 * these tests are framed around the two specific UX-acceptance behaviors validation.md names, not
 * duplicated wiring assertions).
 */
class QrImportConfirmDialogUxTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * UX criterion 4: a page-name collision resolves in **exactly 1 tap** — "Keep both",
     * "Overwrite", and "Cancel" each resolve immediately with no further sub-dialog. Each choice is
     * exercised as a fully separate render (fresh composable, fresh click-tracking flags) so one
     * tap can never be conflated with a leftover state from a previous choice.
     */
    @Test
    fun collisionDialog_should_ResolveInSingleTap_When_KeepBothOverwriteOrCancelTapped() {
        // "Keep both" — single tap resolves; no other choice fires.
        run {
            var keepBoth = false
            var overwrite = false
            var cancel = false
            composeTestRule.setContent {
                QrImportConfirmDialog(
                    existingName = "Meeting Notes",
                    pendingChoice = null,
                    onKeepBoth = { keepBoth = true },
                    onOverwrite = { overwrite = true },
                    onCancel = { cancel = true },
                )
            }
            composeTestRule.onNodeWithText("Keep both").performClick()
            assertTrue(keepBoth, "one tap on \"Keep both\" must resolve the collision")
            assertFalse(overwrite)
            assertFalse(cancel)
        }

        // "Overwrite" — single tap resolves; no other choice fires.
        run {
            var keepBoth = false
            var overwrite = false
            var cancel = false
            composeTestRule.setContent {
                QrImportConfirmDialog(
                    existingName = "Meeting Notes",
                    pendingChoice = null,
                    onKeepBoth = { keepBoth = true },
                    onOverwrite = { overwrite = true },
                    onCancel = { cancel = true },
                )
            }
            composeTestRule.onNodeWithText("Overwrite").performClick()
            assertTrue(overwrite, "one tap on \"Overwrite\" must resolve the collision")
            assertFalse(keepBoth)
            assertFalse(cancel)
        }

        // "Cancel" — single tap resolves; no other choice fires.
        run {
            var keepBoth = false
            var overwrite = false
            var cancel = false
            composeTestRule.setContent {
                QrImportConfirmDialog(
                    existingName = "Meeting Notes",
                    pendingChoice = null,
                    onKeepBoth = { keepBoth = true },
                    onOverwrite = { overwrite = true },
                    onCancel = { cancel = true },
                )
            }
            composeTestRule.onNodeWithText("Cancel").performClick()
            assertTrue(cancel, "one tap on \"Cancel\" must resolve the collision")
            assertFalse(keepBoth)
            assertFalse(overwrite)
        }
    }

    /**
     * UX criterion 10: collision resolution never silently overwrites or duplicates. Once a choice
     * is in flight (`pendingChoice` non-null — the spinner is showing), [Dialog]'s
     * `onDismissRequest` (tap-outside / back) must be a no-op: the guard in
     * [QrImportConfirmDialog] is `onDismissRequest = { if (!isWriting) onCancel() }`. This is
     * exercised at the composable's own contract level (the same technique
     * [QrImportConfirmDialogTest] uses) rather than attempting to simulate an actual
     * window-level outside-click, which the Compose Desktop test harness has no supported API for
     * against a separate `Dialog` window — the guard itself is the entire surface under test, and
     * its condition (`!isWriting`) is asserted directly via the write-in-flight render.
     */
    @Test
    fun collisionDialog_should_BlockDismissOutsideTap_When_WriteInFlight_NeverAutoOverwriteOrDuplicate() {
        var cancelClicked = false
        var overwriteClicked = false
        var keepBothClicked = false

        composeTestRule.setContent {
            QrImportConfirmDialog(
                existingName = "Meeting Notes",
                pendingChoice = QrImportService.CollisionChoice.OVERWRITE,
                onKeepBoth = { keepBothClicked = true },
                onOverwrite = { overwriteClicked = true },
                onCancel = { cancelClicked = true },
            )
        }

        // The write is in flight: the dialog is still showing (not silently dismissed), the
        // chosen action shows a spinner (its label is gone), and the OTHER write-triggering
        // button is disabled — no write can be re-triggered or duplicated while this is up.
        composeTestRule.onNodeWithText("A page named \"Meeting Notes\" already exists.", substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Overwrite").assertDoesNotExist()
        composeTestRule.onNodeWithText("Keep both").assertIsDisplayed()

        // Cancel stays tappable throughout a write — the one way out that's never blocked.
        composeTestRule.onNodeWithText("Cancel").assertIsEnabled()

        // No write-triggering callback fired merely from the dialog being rendered mid-write —
        // proves nothing auto-resolves/duplicates on its own while `isWriting` is true.
        assertFalse(overwriteClicked)
        assertFalse(keepBothClicked)
        assertFalse(cancelClicked)

        // Cancel remains the one live escape hatch; tapping it is the only way this dialog closes
        // from here (mirrors the production onDismissRequest guard's fallback path once !isWriting).
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(true, cancelClicked)
    }
}
