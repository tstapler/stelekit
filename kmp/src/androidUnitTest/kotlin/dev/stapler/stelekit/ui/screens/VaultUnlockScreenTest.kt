// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.ui.VaultState
import dev.stapler.stelekit.vault.VaultError
import dev.stapler.stelekit.vault.VaultNamespace
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Robolectric Compose tests for [VaultUnlockScreen] covering error mapping and unlock paths. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class VaultUnlockScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val unlocks = mutableListOf<Pair<String, VaultNamespace>>()

    private fun show(state: VaultState = VaultState.Locked) {
        composeTestRule.setContent {
            MaterialTheme {
                VaultUnlockScreen(
                    graphName = "My Vault",
                    vaultState = state,
                    onUnlock = { chars, ns -> unlocks += String(chars) to ns },
                )
            }
        }
    }

    @Test
    fun vault_should_showGraphNameAndDisableUnlock_when_passphraseBlank() {
        show()
        composeTestRule.onNodeWithText("My Vault").assertExists()
        composeTestRule.onNodeWithText("Unlock").assertIsNotEnabled()
    }

    @Test
    fun vault_should_unlockOuter_when_passphraseEnteredAndUnlockClicked() {
        show()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("hunter2")
        composeTestRule.onNodeWithText("Unlock").assertIsEnabled().performClick()
        assertEquals(listOf("hunter2" to VaultNamespace.OUTER), unlocks)
    }

    @Test
    fun vault_should_toggleHiddenOption_and_unlockHidden() {
        show()
        assertTrue(composeTestRule.onAllNodesWithText("Open alternate graph").fetchSemanticsNodes().isEmpty())
        composeTestRule.onNodeWithText("Advanced").performClick()
        composeTestRule.onNodeWithText("Cancel").assertExists()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("secret")
        composeTestRule.onNodeWithText("Open alternate graph").performClick()
        assertEquals(listOf("secret" to VaultNamespace.HIDDEN), unlocks)
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Advanced").assertExists()
    }

    @Test
    fun vault_should_toggleVisibility_when_eyeClicked() {
        show()
        composeTestRule.onNodeWithContentDescription("Show passphrase").performClick()
        composeTestRule.onNodeWithContentDescription("Hide passphrase").assertExists()
    }

    @Test
    fun vault_should_showUnlockingAndDisableInputs_when_unlocking() {
        show(VaultState.Unlocking)
        composeTestRule.onNodeWithText("Unlocking...").assertExists()
        // A disabled text field drops its SetText action, so match it by label instead.
        composeTestRule.onNodeWithText("Passphrase").assertIsNotEnabled()
    }

    @Test
    fun vault_should_showIncorrectPassphrase_when_invalidCredential() {
        show(VaultState.Error(VaultError.InvalidCredential()))
        composeTestRule.onNodeWithText("Incorrect passphrase.").assertExists()
    }

    @Test
    fun vault_should_showTamperedAsIncorrectPassphrase() {
        show(VaultState.Error(VaultError.HeaderTampered()))
        composeTestRule.onNodeWithText("Incorrect passphrase.").assertExists()
    }

    @Test
    fun vault_should_showCorruptedMessage() {
        show(VaultState.Error(VaultError.CorruptedFile()))
        composeTestRule.onNodeWithText("Vault file is corrupted or truncated.").assertExists()
    }

    @Test
    fun vault_should_showUnsupportedVersionMessage() {
        show(VaultState.Error(VaultError.UnsupportedVersion(9)))
        composeTestRule.onNodeWithText("Unsupported vault format version.").assertExists()
    }

    @Test
    fun vault_should_showNotAVaultMessage() {
        show(VaultState.Error(VaultError.NotAVault()))
        composeTestRule.onNodeWithText("Vault file is missing", substring = true).assertExists()
    }

    @Test
    fun vault_should_showGenericMessage_forOtherErrors() {
        show(VaultState.Error(VaultError.SlotsFull()))
        composeTestRule.onNodeWithText("Vault error. Please try again or contact support.").assertExists()
    }
}
