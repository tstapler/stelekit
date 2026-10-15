// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitCredentialConnection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Robolectric Compose tests for [Step3Auth]: one test per auth-type branch plus its callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class GitSetupStep3AuthTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun conn(id: String, label: String, type: GitAuthType = GitAuthType.HTTPS_TOKEN) =
        GitCredentialConnection(id = id, host = "github.com", accountLabel = label, authType = type, createdAt = 0L)

    private fun show(
        authType: GitAuthType = GitAuthType.NONE,
        onAuthTypeChange: (GitAuthType) -> Unit = {},
        tokenVisible: Boolean = false,
        onToggleTokenVisible: () -> Unit = {},
        onBack: () -> Unit = {},
        onNext: () -> Unit = {},
        httpsConnections: List<GitCredentialConnection> = emptyList(),
        selectedHttpsConnectionId: String? = null,
        onSelectHttpsConnection: (GitCredentialConnection?) -> Unit = {},
        oauthConnections: List<GitCredentialConnection> = emptyList(),
        selectedOauthConnectionId: String? = null,
        onSelectOauthConnection: (GitCredentialConnection?) -> Unit = {},
        oauthConnectedAs: String? = null,
        onStartOAuthFlow: () -> Unit = {},
        deviceFlowEnabled: Boolean = false,
        onBrowseSshKey: (() -> Unit)? = null,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                Step3Auth(
                    authType = authType,
                    onAuthTypeChange = onAuthTypeChange,
                    sshKeyPath = "",
                    onSshKeyPathChange = {},
                    httpsToken = "",
                    onHttpsTokenChange = {},
                    tokenVisible = tokenVisible,
                    onToggleTokenVisible = onToggleTokenVisible,
                    sshPassphrase = "",
                    onSshPassphraseChange = {},
                    onBack = onBack,
                    onNext = onNext,
                    httpsConnections = httpsConnections,
                    selectedHttpsConnectionId = selectedHttpsConnectionId,
                    onSelectHttpsConnection = onSelectHttpsConnection,
                    oauthConnections = oauthConnections,
                    selectedOauthConnectionId = selectedOauthConnectionId,
                    onSelectOauthConnection = onSelectOauthConnection,
                    oauthConnectedAs = oauthConnectedAs,
                    onStartOAuthFlow = onStartOAuthFlow,
                    deviceFlowEnabled = deviceFlowEnabled,
                    onBrowseSshKey = onBrowseSshKey,
                )
            }
        }
    }

    @Test
    fun step3Auth_should_showFourAuthTypes_when_noneSelected() {
        show()
        composeTestRule.onNodeWithText("No authentication (public repo)").assertExists()
        composeTestRule.onNodeWithText("SSH key").assertExists()
        composeTestRule.onNodeWithText("HTTPS token (GitHub PAT, etc.)").assertExists()
        composeTestRule.onNodeWithText("GitHub (OAuth)").assertExists()
        composeTestRule.onNodeWithText("Personal access token").assertDoesNotExist()
    }

    @Test
    fun step3Auth_should_reportAuthTypeChange_when_rowClicked() {
        val picked = mutableListOf<GitAuthType>()
        show(onAuthTypeChange = { picked += it })
        composeTestRule.onNodeWithText("SSH key").performClick()
        composeTestRule.onNodeWithText("HTTPS token (GitHub PAT, etc.)").performClick()
        composeTestRule.onNodeWithText("GitHub (OAuth)").performClick()
        composeTestRule.onNodeWithText("No authentication (public repo)").performClick()
        assertEquals(
            listOf(GitAuthType.SSH_KEY, GitAuthType.HTTPS_TOKEN, GitAuthType.GITHUB_OAUTH, GitAuthType.NONE),
            picked,
        )
    }

    @Test
    fun step3Auth_should_showSshFields_when_authTypeIsSshKey() {
        show(authType = GitAuthType.SSH_KEY)
        composeTestRule.onNodeWithText("SSH private key path (e.g. ~/.ssh/id_ed25519)").assertExists()
        composeTestRule.onNodeWithText("SSH key passphrase (leave empty if none)").assertExists()
        composeTestRule.onNodeWithText("Personal access token").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Browse for SSH key file").assertDoesNotExist()
    }

    @Test
    fun step3Auth_should_toggleBrowseAndPassphraseVisibility_when_sshSectionShown() {
        var browsed = false
        show(authType = GitAuthType.SSH_KEY, onBrowseSshKey = { browsed = true })
        composeTestRule.onNodeWithContentDescription("Browse for SSH key file").performClick()
        assertTrue(browsed)
        composeTestRule.onNodeWithContentDescription("Show passphrase").performClick()
        composeTestRule.onNodeWithContentDescription("Hide passphrase").assertExists()
    }

    @Test
    fun step3Auth_should_showTokenField_when_authTypeIsHttpsToken() {
        var toggled = 0
        show(authType = GitAuthType.HTTPS_TOKEN, onToggleTokenVisible = { toggled++ })
        composeTestRule.onNodeWithText("Personal access token").assertExists()
        composeTestRule.onNodeWithContentDescription("Show token").performClick()
        assertEquals(1, toggled)
    }

    @Test
    fun step3Auth_should_showHideLabel_when_tokenIsVisible() {
        show(authType = GitAuthType.HTTPS_TOKEN, tokenVisible = true)
        composeTestRule.onNodeWithContentDescription("Hide token").assertExists()
    }

    @Test
    fun step3Auth_should_listSavedTokens_and_hideNewTokenField_when_oneSelected() {
        val selected = mutableListOf<GitCredentialConnection?>()
        val saved = conn("c1", "work-pat")
        show(
            authType = GitAuthType.HTTPS_TOKEN,
            httpsConnections = listOf(saved),
            selectedHttpsConnectionId = "c1",
            onSelectHttpsConnection = { selected += it },
        )
        composeTestRule.onNodeWithText("Saved tokens").assertExists()
        composeTestRule.onNodeWithText("work-pat").assertExists()
        composeTestRule.onNodeWithText("Personal access token").assertDoesNotExist()
        composeTestRule.onNodeWithText("Use a new token").performClick()
        assertEquals(listOf<GitCredentialConnection?>(null), selected)
    }

    @Test
    fun step3Auth_should_selectSavedToken_when_rowClicked() {
        val selected = mutableListOf<GitCredentialConnection?>()
        val saved = conn("c1", "work-pat")
        show(
            authType = GitAuthType.HTTPS_TOKEN,
            httpsConnections = listOf(saved),
            onSelectHttpsConnection = { selected += it },
        )
        composeTestRule.onNodeWithText("Personal access token").assertExists()
        composeTestRule.onNodeWithText("work-pat").performClick()
        assertEquals(listOf<GitCredentialConnection?>(saved), selected)
    }

    @Test
    fun step3Auth_should_disableConnect_when_deviceFlowUnavailable() {
        show(authType = GitAuthType.GITHUB_OAUTH, deviceFlowEnabled = false)
        composeTestRule.onNodeWithText("Connect GitHub Account").assertIsNotEnabled()
        composeTestRule.onNodeWithText("GitHub OAuth is not available on this platform.").assertExists()
    }

    @Test
    fun step3Auth_should_startOAuthFlow_when_connectClicked() {
        var started = 0
        show(authType = GitAuthType.GITHUB_OAUTH, deviceFlowEnabled = true, onStartOAuthFlow = { started++ })
        composeTestRule.onNodeWithText("Connect GitHub Account").assertIsEnabled().performClick()
        assertEquals(1, started)
    }

    @Test
    fun step3Auth_should_offerSavedAccounts_when_disconnectedWithConnections() {
        val selected = mutableListOf<GitCredentialConnection?>()
        val acct = conn("o1", "octocat", GitAuthType.GITHUB_OAUTH)
        show(
            authType = GitAuthType.GITHUB_OAUTH,
            oauthConnections = listOf(acct),
            onSelectOauthConnection = { selected += it },
        )
        composeTestRule.onNodeWithText("Saved accounts").assertExists()
        composeTestRule.onNodeWithText("Or connect a new account below.").assertExists()
        composeTestRule.onNodeWithText("@octocat").performClick()
        assertEquals(listOf<GitCredentialConnection?>(acct), selected)
    }

    @Test
    fun step3Auth_should_showConnectedBanner_and_switchAccounts_when_connected() {
        val selected = mutableListOf<GitCredentialConnection?>()
        val a = conn("o1", "octocat", GitAuthType.GITHUB_OAUTH)
        val b = conn("o2", "hubot", GitAuthType.GITHUB_OAUTH)
        show(
            authType = GitAuthType.GITHUB_OAUTH,
            oauthConnections = listOf(a, b),
            selectedOauthConnectionId = "o1",
            oauthConnectedAs = "octocat",
            deviceFlowEnabled = true,
            onSelectOauthConnection = { selected += it },
        )
        composeTestRule.onNodeWithText("Connected as @octocat").assertExists()
        composeTestRule.onNodeWithText("Switch account").assertExists()
        composeTestRule.onNodeWithText("@hubot").performClick()
        assertEquals(listOf<GitCredentialConnection?>(b), selected)
        composeTestRule.onNodeWithText("Connect a different account").assertIsEnabled()
    }

    @Test
    fun step3Auth_should_wireBackAndNext_when_navButtonsClicked() {
        var back = 0
        var next = 0
        show(onBack = { back++ }, onNext = { next++ })
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.onNodeWithText("Next").performClick()
        assertEquals(1, back)
        assertEquals(1, next)
    }
}
