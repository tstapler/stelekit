// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.platform.HostAccessState
import dev.stapler.stelekit.ui.components.settings.FolderSyncSettings
import dev.stapler.stelekit.ui.components.settings.ReconciliationUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Tall screen: the unscrollable settings column clips lower sections on the 320x470 default.
@Config(qualifiers = "w480dp-h1600dp")
@RunWith(RobolectricTestRunner::class)
class FolderSyncSettingsTest {
    private companion object {
        const val ENABLE_SYNC = "Enable live folder sync"
        const val MOVE_STORAGE = "Move storage location…"
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(
        hostAccessState: HostAccessState,
        supportsNativeDirectoryPicker: Boolean = true,
        onConnect: suspend () -> ReconciliationUiState = { ReconciliationUiState.Summary(0, 0, 0, 0) },
        onMoveStorageLocation: (suspend () -> StorageLocation)? = null,
        onUnlink: (suspend () -> Unit)? = null,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                FolderSyncSettings(
                    hostAccessState = hostAccessState,
                    supportsNativeDirectoryPicker = supportsNativeDirectoryPicker,
                    onConnect = onConnect,
                    onMoveStorageLocation = onMoveStorageLocation,
                    onUnlink = onUnlink,
                )
            }
        }
    }

    private fun assertAbsent(text: String) =
        assertTrue(composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty(), "'$text' should not be shown")

    @Test
    fun notConnected_should_ShowEnableButtonAndReassurance() {
        show(HostAccessState.NotApplicable)

        composeTestRule.onNodeWithText(ENABLE_SYNC).assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Existing edits in this graph are kept — nothing is overwritten when you connect.")
            .assertIsDisplayed()
    }

    @Test
    fun connectButton_should_InvokeOnConnectAndShowSummary_WhenClicked() {
        var connects = 0
        show(
            HostAccessState.NotApplicable,
            onConnect = {
                connects++
                ReconciliationUiState.Summary(identical = 3, hostChangedConflict = 0, hostOnlyNew = 0, browserOnlyNeedsPush = 0)
            },
        )

        composeTestRule.onNodeWithText(ENABLE_SYNC).performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, connects)
        composeTestRule.onNodeWithText("Folder sync enabled").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 files already match").assertIsDisplayed()
    }

    @Test
    fun connectFailure_should_ShowFailedState() {
        show(HostAccessState.NotApplicable, onConnect = { error("boom") })

        composeTestRule.onNodeWithText(ENABLE_SYNC).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Couldn't finish comparing your files").assertIsDisplayed()
    }

    @Test
    fun doneButton_should_ReturnToEnableAffordance() {
        show(HostAccessState.NotApplicable)

        composeTestRule.onNodeWithText(ENABLE_SYNC).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Done").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(ENABLE_SYNC).assertIsDisplayed()
    }

    @Test
    fun pickerNotSupported_should_HideConnectAffordance() {
        show(HostAccessState.NotApplicable, supportsNativeDirectoryPicker = false)

        assertAbsent(ENABLE_SYNC)
    }

    @Test
    fun alreadyConnected_should_HideConnectAffordance() {
        show(HostAccessState.Granted)

        assertAbsent(ENABLE_SYNC)
    }

    @Test
    fun moveStorage_should_BeHidden_WhenCallbackNull() {
        show(HostAccessState.NotApplicable, onMoveStorageLocation = null)

        assertAbsent(MOVE_STORAGE)
    }

    @Test
    fun moveStorage_should_ResolveLocation_WhenClicked() {
        var resolved = 0
        show(
            HostAccessState.NotApplicable,
            onMoveStorageLocation = {
                resolved++
                StorageLocation.AppOwned("g1")
            },
        )

        composeTestRule.onNodeWithText(MOVE_STORAGE).assertIsDisplayed()
        composeTestRule.onNodeWithText(MOVE_STORAGE).performClick()
        composeTestRule.waitForIdle()

        // AppOwned with no onBrowseRequestForMove is a logged no-op; the button re-enables.
        assertEquals(1, resolved)
        composeTestRule.onNodeWithText(MOVE_STORAGE).assertIsDisplayed()
    }

    @Test
    fun unlink_should_InvokeCallback_WhenLinked() {
        var unlinked = 0
        show(HostAccessState.Granted, onUnlink = { unlinked++ })

        composeTestRule.onNodeWithText("Unlink folder").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, unlinked)
    }

    @Test
    fun unlink_should_BeHidden_WhenNotLinked() {
        show(HostAccessState.Unlinked, onUnlink = {})

        assertAbsent("Unlink folder")
    }
}
