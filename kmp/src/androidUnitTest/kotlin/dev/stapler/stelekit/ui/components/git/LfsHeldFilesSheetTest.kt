// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.git

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.git.lfs.LfsViolation
import dev.stapler.stelekit.git.lfs.LfsViolationReason
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.ui.components.GitSyncStatus
import dev.stapler.stelekit.ui.components.SyncStatusBadge
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compose behaviour, so it lives in androidUnitTest (Robolectric, no display needed). */
@RunWith(RobolectricTestRunner::class)
class LfsHeldFilesSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rawPhoto = LfsViolation("assets/photo.png", LfsViolationReason.RawBinary, ".gitattributes:1")

    @Test
    fun sheet_should_ListTheHeldPathWithItsReasonRuleAndRemedy() {
        composeRule.setContent { MaterialTheme { LfsHeldFilesSheet(held = listOf(rawPhoto), onDismiss = {}) } }

        composeRule.onNodeWithText("assets/photo.png").assertIsDisplayed()
        composeRule.onNodeWithText(
            "This repository stores that path with Git LFS and SteleKit cannot upload it here yet. The file was not committed.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Rule: .gitattributes:1").assertIsDisplayed()
        composeRule.onNodeWithText(LFS_HELD_REMEDY).assertIsDisplayed()
    }

    @Test
    fun sheet_should_ExplainACorruptPointerDifferentlyFromARawBinary() {
        val corrupt = LfsViolation("assets/p.png", LfsViolationReason.CorruptPointer)

        composeRule.setContent { MaterialTheme { LfsHeldFilesSheet(held = listOf(corrupt), onDismiss = {}) } }

        composeRule.onNodeWithText(lfsHeldReasonText(LfsViolationReason.CorruptPointer)).assertIsDisplayed()
    }

    @Test
    fun sheet_should_CallOnDismiss_When_CloseIsTapped() {
        var dismissed = 0
        composeRule.setContent { MaterialTheme { LfsHeldFilesSheet(held = listOf(rawPhoto), onDismiss = { dismissed++ }) } }

        composeRule.onNodeWithText("Close").performClick()

        assertEquals(1, dismissed)
    }

    @Test
    fun badge_should_ShowAWarningChipThatOpensTheSheet_When_FilesAreHeld() {
        composeRule.setContent {
            MaterialTheme {
                SyncStatusBadge(
                    status = GitSyncStatus(SyncState.LfsActionRequired(listOf(rawPhoto))),
                    onSyncClick = {},
                )
            }
        }

        composeRule.onNodeWithText("1 file held").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("1 file held by Git LFS — tap for details").performClick()

        composeRule.onNodeWithText(LFS_HELD_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText("assets/photo.png").assertIsDisplayed()
    }

    @Test
    fun badge_should_PluraliseTheLabel_When_SeveralFilesAreHeld() {
        val held = listOf(rawPhoto, LfsViolation("assets/b.png", LfsViolationReason.RawBinary))
        composeRule.setContent {
            MaterialTheme { SyncStatusBadge(status = GitSyncStatus(SyncState.LfsActionRequired(held)), onSyncClick = {}) }
        }

        composeRule.onNodeWithText("2 files held").assertIsDisplayed()
    }

    @Test
    fun diagnostics_should_ListEveryPathReasonAndRule() {
        val text = lfsHeldDiagnostics(listOf(rawPhoto, LfsViolation("assets/b.png", LfsViolationReason.CorruptPointer)))

        assertTrue("assets/photo.png: RawBinary (rule .gitattributes:1)" in text, text)
        assertTrue("assets/b.png: CorruptPointer" in text, text)
    }
}
