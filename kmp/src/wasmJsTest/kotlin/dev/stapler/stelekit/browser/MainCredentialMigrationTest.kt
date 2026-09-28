// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.browser

import dev.stapler.stelekit.platform.EphemeralSettingsMode
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlinx.browser.localStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * web-credential-persistence (Task 1.4.2a): [migrateLegacyGitCredential] one-time-migrates
 * `persistWebGitCredentials`'s legacy plaintext `githubToken` `PlatformSettings` value into the
 * real, encrypted [CredentialStore] — proves it runs exactly once (idempotent), and never touches
 * real `localStorage` under [EphemeralSettingsMode].
 */
class MainCredentialMigrationTest {

    @BeforeTest
    fun setUp() = runTest {
        CredentialStore.resetForTest()
        clearLegacyGithubKeys()
        CredentialStore.preload()
    }

    @AfterTest
    fun tearDown() {
        CredentialStore.resetForTest()
        EphemeralSettingsMode.resetForTest()
        clearLegacyGithubKeys()
    }

    // migrateLegacyGitCredential clears legacy keys via PlatformSettings.putString(key, ""), which
    // writes an empty string, not a removed key — so a prior test's real localStorage["githubToken"]
    // entry survives as "" rather than null unless explicitly removed here between test cases.
    private fun clearLegacyGithubKeys() {
        localStorage.removeItem("githubOwner")
        localStorage.removeItem("githubRepo")
        localStorage.removeItem("githubBranch")
        localStorage.removeItem("githubToken")
    }

    @Test
    fun `migrateLegacyGitCredential migrates the legacy plaintext token exactly once and clears the legacy keys`() = runTest {
        PlatformSettings().putString("githubToken", "ghp_legacy456")

        migrateLegacyGitCredential("default")

        assertEquals("ghp_legacy456", CredentialStore().retrieve("git_https_token_default"))
        assertEquals("", PlatformSettings().getString("githubToken", ""))
    }

    @Test
    fun `migrateLegacyGitCredential no-ops on a repeat run once the target key is already migrated`() = runTest {
        CredentialStore().store("git_https_token_default", "ghp_alreadyMigrated")
        CredentialStore.flushForTest()
        PlatformSettings().putString("githubToken", "ghp_shouldNotOverwrite")

        migrateLegacyGitCredential("default")

        assertEquals("ghp_alreadyMigrated", CredentialStore().retrieve("git_https_token_default"))
        assertEquals("ghp_shouldNotOverwrite", PlatformSettings().getString("githubToken", ""))
    }

    @Test
    fun `migrateLegacyGitCredential is a no-op when there is no legacy token to migrate`() = runTest {
        migrateLegacyGitCredential("default")

        assertNull(CredentialStore().retrieve("git_https_token_default"))
    }

    @Test
    fun `migrateLegacyGitCredential leaves real localStorage untouched under EphemeralSettingsMode`() = runTest {
        EphemeralSettingsMode.enable()
        PlatformSettings().putString("githubToken", "ghp_ephemeralLegacy")

        migrateLegacyGitCredential("default")

        assertEquals("ghp_ephemeralLegacy", CredentialStore().retrieve("git_https_token_default"))
        assertNull(localStorage.getItem("githubToken"))
    }
}
