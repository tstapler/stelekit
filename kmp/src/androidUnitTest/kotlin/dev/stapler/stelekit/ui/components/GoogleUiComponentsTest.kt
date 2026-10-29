// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.platform.google.GooglePhotosImportState
import dev.stapler.stelekit.ui.components.settings.GoogleAccountSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Toolbar "From Google Photos" entry and the Google account settings card, rendered under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GoogleUiComponentsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state(
        signedIn: Boolean = true,
        status: GooglePhotosImportState = GooglePhotosImportState.Idle,
        onImport: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) = GooglePhotosToolbarState(signedIn, status, onImport, onDismiss)

    private fun showButtonAndLine(s: GooglePhotosToolbarState) = composeTestRule.setContent {
        MaterialTheme {
            androidx.compose.foundation.layout.Column {
                GooglePhotosToolbarButton(s)
                GooglePhotosStatusLine(s)
            }
        }
    }

    @Test
    fun toolbarButton_should_FireImport_When_SignedInAndIdle() {
        var imports = 0
        showButtonAndLine(state(onImport = { imports++ }))

        composeTestRule.onNodeWithContentDescription("From Google Photos").assertIsEnabled().performClick()

        assertEquals(1, imports)
        composeTestRule.onNodeWithText(GOOGLE_PHOTOS_SIGNED_OUT_REASON).assertDoesNotExist()
    }

    @Test
    fun toolbarButton_should_BeDisabledWithReason_When_SignedOut() {
        showButtonAndLine(state(signedIn = false))

        composeTestRule.onNodeWithContentDescription("From Google Photos. $GOOGLE_PHOTOS_SIGNED_OUT_REASON")
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText(GOOGLE_PHOTOS_SIGNED_OUT_REASON).assertExists()
    }

    @Test
    fun toolbarButton_should_BeDisabledAndShowProgress_When_OpeningOrImporting() {
        showButtonAndLine(state(status = GooglePhotosImportState.Opening))
        composeTestRule.onNodeWithContentDescription("From Google Photos").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Opening Google Photos…").assertExists()
    }

    @Test
    fun statusLine_should_ShowImportingText_When_Importing() {
        showButtonAndLine(state(status = GooglePhotosImportState.Importing))
        composeTestRule.onNodeWithText("Importing from Google Photos…").assertExists()
        composeTestRule.onNodeWithContentDescription("From Google Photos").assertIsNotEnabled()
    }

    @Test
    fun statusLine_should_OfferRetryAndDismiss_When_Failed() {
        var imports = 0
        var dismissals = 0
        showButtonAndLine(
            state(status = GooglePhotosImportState.Failed("Download failed"), onImport = { imports++ }, onDismiss = { dismissals++ }),
        )

        composeTestRule.onNodeWithText("Download failed").assertExists()
        composeTestRule.onNodeWithText("Retry").performClick()
        composeTestRule.onNodeWithText("Dismiss").performClick()

        assertEquals(1, imports)
        assertEquals(1, dismissals)
    }

    @Test
    fun statusLine_should_BeEmpty_When_SignedInAndIdle() {
        showButtonAndLine(state())
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
        composeTestRule.onNodeWithText("Opening Google Photos…").assertDoesNotExist()
    }

    @Test
    fun accountSettings_should_ShowEmailDisconnectAndBrowseDrive_When_Connected() {
        var disconnects = 0
        var browses = 0
        composeTestRule.setContent {
            MaterialTheme {
                GoogleAccountSettings(
                    isAuthenticated = true, connectedEmail = "me@example.com", isConnecting = false,
                    errorMessage = null, onConnect = {}, onDisconnect = { disconnects++ },
                    onBrowseDrive = { browses++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Connected as me@example.com").assertExists()
        composeTestRule.onNodeWithText("Browse Drive").performClick()
        composeTestRule.onNodeWithText("Disconnect Google Account").performClick()
        assertEquals(1, browses)
        assertEquals(1, disconnects)
    }

    @Test
    fun accountSettings_should_HideBrowseDrive_When_NoBrowseCallback() {
        composeTestRule.setContent {
            MaterialTheme {
                GoogleAccountSettings(true, "me@example.com", false, null, {}, {})
            }
        }
        composeTestRule.onNodeWithText("Browse Drive").assertDoesNotExist()
    }

    @Test
    fun accountSettings_should_ConnectAndShowError_When_Disconnected() {
        var connects = 0
        composeTestRule.setContent {
            MaterialTheme {
                GoogleAccountSettings(false, null, false, "Sign-in failed", { connects++ }, {})
            }
        }

        composeTestRule.onNodeWithText("No Google account connected").assertExists()
        composeTestRule.onNodeWithText("Sign-in failed").assertExists()
        composeTestRule.onNodeWithText("Connect Google Account").performClick()
        assertEquals(1, connects)
    }

    @Test
    fun accountSettings_should_DisableConnect_When_Connecting() {
        composeTestRule.setContent {
            MaterialTheme {
                GoogleAccountSettings(false, null, true, null, {}, {})
            }
        }
        composeTestRule.onNodeWithText("Connecting…").assertExists()
        composeTestRule.onNodeWithText("Connect Google Account").assertDoesNotExist()
    }
}
