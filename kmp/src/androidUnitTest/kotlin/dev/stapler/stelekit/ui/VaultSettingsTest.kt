// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.ui.components.settings.VaultSettings
import dev.stapler.stelekit.vault.VaultError
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Tall screen: the unscrollable settings column clips the passphrase form on the 320x470 default.
@Config(qualifiers = "w480dp-h1600dp")
@RunWith(RobolectricTestRunner::class)
class VaultSettingsTest {
    private companion object {
        const val ANIMATION_SETTLE_MS = 1_000L
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(
        isParanoidMode: Boolean,
        isVaultUnlocked: Boolean = false,
        onCreateVault: (suspend (CharArray) -> Either<VaultError, Unit>)? = null,
        onAddKeyslot: (suspend (CharArray) -> Either<VaultError, Unit>)? = null,
        onRemoveKeyslot: (suspend (Int) -> Either<VaultError, Unit>)? = null,
        onLockVault: (() -> Unit)? = null,
        onListActiveSlots: (suspend () -> List<Int>)? = null,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                VaultSettings(
                    isParanoidMode = isParanoidMode,
                    isVaultUnlocked = isVaultUnlocked,
                    onCreateVault = onCreateVault,
                    onAddKeyslot = onAddKeyslot,
                    onRemoveKeyslot = onRemoveKeyslot,
                    onLockVault = onLockVault,
                    onListActiveSlots = onListActiveSlots,
                )
            }
        }
    }

    private fun settle() = composeTestRule.mainClock.advanceTimeBy(ANIMATION_SETTLE_MS)

    @Test
    fun status_should_ShowEncryptionDisabled_WhenNotParanoid() {
        show(isParanoidMode = false)

        composeTestRule.onNodeWithText("Encryption disabled").assertIsDisplayed()
        composeTestRule.onNodeWithText("Files are stored as plain markdown.").assertIsDisplayed()
    }

    @Test
    fun status_should_ShowUnlocked_WhenParanoidAndUnlocked() {
        show(isParanoidMode = true, isVaultUnlocked = true)

        composeTestRule.onNodeWithText("Paranoid mode enabled").assertIsDisplayed()
        composeTestRule.onNodeWithText("Graph is unlocked. Files are encrypted at rest.").assertIsDisplayed()
    }

    @Test
    fun status_should_ShowLocked_WhenParanoidAndLocked() {
        show(isParanoidMode = true, isVaultUnlocked = false)

        composeTestRule.onNodeWithText("Graph is locked.").assertIsDisplayed()
    }

    @Test
    fun createSection_should_BeHidden_WhenCallbackNullOrAlreadyEncrypted() {
        show(isParanoidMode = false, onCreateVault = null)
        composeTestRule.onAllNodesWithText("Enable encryption…").assertCountEquals(0)
    }

    @Test
    fun createSection_should_BeHidden_WhenAlreadyEncrypted() {
        show(isParanoidMode = true, isVaultUnlocked = true, onCreateVault = { Unit.right() })
        composeTestRule.onAllNodesWithText("Enable encryption…").assertCountEquals(0)
    }

    @Test
    fun createVault_should_PassPassphraseToCallback_WhenConfirmed() {
        var received: String? = null
        show(isParanoidMode = false, onCreateVault = { received = String(it); Unit.right() })

        composeTestRule.onNodeWithText("Enable encryption…").performClick()
        settle()
        composeTestRule.onNodeWithText("Passphrase").performTextInput("correct horse")
        composeTestRule.onNodeWithText("Confirm passphrase").performTextInput("correct horse")
        composeTestRule.onNodeWithText("Encrypt graph").performClick()
        composeTestRule.waitUntil(5_000) { received != null }

        assertEquals("correct horse", received)
    }

    @Test
    fun createVault_should_ShowMismatchHint_AndDisableSubmit_WhenConfirmDiffers() {
        var called = false
        show(isParanoidMode = false, onCreateVault = { called = true; Unit.right() })

        composeTestRule.onNodeWithText("Enable encryption…").performClick()
        settle()
        composeTestRule.onNodeWithText("Passphrase").performTextInput("one")
        composeTestRule.onNodeWithText("Confirm passphrase").performTextInput("two")

        composeTestRule.onNodeWithText("Passphrases do not match").assertIsDisplayed()
        composeTestRule.onNodeWithText("Encrypt graph").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Encrypt graph").performClick()
        composeTestRule.waitForIdle()
        assertFalse(called)
    }

    @Test
    fun createVault_should_DisplayError_WhenCallbackFails() {
        show(
            isParanoidMode = false,
            onCreateVault = { VaultError.InvalidCredential("Disk is read-only").left() },
        )

        composeTestRule.onNodeWithText("Enable encryption…").performClick()
        settle()
        composeTestRule.onNodeWithText("Passphrase").performTextInput("secret")
        composeTestRule.onNodeWithText("Confirm passphrase").performTextInput("secret")
        composeTestRule.onNodeWithText("Encrypt graph").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Disk is read-only").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText("Disk is read-only").assertIsDisplayed()
    }

    @Test
    fun createVault_cancel_should_RestoreEnableButton() {
        show(isParanoidMode = false, onCreateVault = { Unit.right() })

        composeTestRule.onNodeWithText("Enable encryption…").performClick()
        settle()
        composeTestRule.onNodeWithText("Cancel").performClick()
        settle()

        composeTestRule.onNodeWithText("Enable encryption…").assertIsDisplayed()
    }

    @Test
    fun lockButton_should_InvokeCallback_WhenUnlocked() {
        var locked = false
        show(isParanoidMode = true, isVaultUnlocked = true, onLockVault = { locked = true })

        composeTestRule.onNodeWithText("Lock vault").performClick()

        assertTrue(locked)
    }

    @Test
    fun lockButton_should_BeAbsent_WhenVaultLocked() {
        show(isParanoidMode = true, isVaultUnlocked = false, onLockVault = {})
        composeTestRule.onAllNodesWithText("Lock vault").assertCountEquals(0)
    }

    @Test
    fun keyslots_should_ListPassphrases_AndAddNewOne() {
        val slots = mutableListOf(0)
        var added: String? = null
        show(
            isParanoidMode = true,
            isVaultUnlocked = true,
            onListActiveSlots = { slots.toList() },
            onAddKeyslot = { added = String(it); slots += 1; Unit.right() },
        )
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Passphrase 1").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText("Add passphrase").performClick()
        settle()
        composeTestRule.onNodeWithText("Passphrase").performTextInput("second")
        composeTestRule.onNodeWithText("Confirm passphrase").performTextInput("second")
        composeTestRule.onNodeWithText("Add").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Passphrase 2").fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals("second", added)
    }

    @Test
    fun keyslots_should_ShowRemoveError_WhenRemovalFails() {
        show(
            isParanoidMode = true,
            isVaultUnlocked = true,
            onListActiveSlots = { listOf(0, 1) },
            onRemoveKeyslot = { VaultError.InvalidCredential("Cannot remove last unlock").left() },
        )
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Passphrase 2").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithContentDescription("Remove passphrase 1").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Cannot remove last unlock").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText("Cannot remove last unlock").assertIsDisplayed()
    }
}
