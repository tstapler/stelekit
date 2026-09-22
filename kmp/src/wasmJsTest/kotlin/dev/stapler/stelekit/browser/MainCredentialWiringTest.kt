// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.browser

import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * web-credential-persistence (Task 1.4.1a): [resolveGitHttpsToken] is the extracted, directly
 * testable token-resolution expression `configResolver` uses in both `main()` boot branches —
 * proves it prefers the real [CredentialStore] over the legacy [PlatformFileSystem.githubToken]
 * plaintext fallback, and that the fallback still covers the transition window before migration
 * (`migrateLegacyGitCredential`, see [MainCredentialMigrationTest]) has run.
 */
class MainCredentialWiringTest {

    private val originalLegacyToken = PlatformFileSystem.githubToken

    @BeforeTest
    fun setUp() = runTest {
        CredentialStore.resetForTest()
        CredentialStore.preload()
    }

    @AfterTest
    fun tearDown() {
        CredentialStore.resetForTest()
        PlatformFileSystem.githubToken = originalLegacyToken
    }

    @Test
    fun `resolveGitHttpsToken prefers the value from CredentialStore over the legacy PlatformFileSystem githubToken fallback`() = runTest {
        CredentialStore().store("git_https_token_myGraphId", "ghp_realtoken")
        CredentialStore.flushForTest()
        PlatformFileSystem.githubToken = "ghp_legacy"

        assertEquals("ghp_realtoken", resolveGitHttpsToken("git_https_token_myGraphId"))
    }

    @Test
    fun `resolveGitHttpsToken falls back to PlatformFileSystem githubToken when CredentialStore has no value yet`() {
        PlatformFileSystem.githubToken = "ghp_legacy"

        assertEquals("ghp_legacy", resolveGitHttpsToken("git_https_token_myGraphId"))
    }

    @Test
    fun `resolveGitHttpsToken returns empty string when neither CredentialStore nor the legacy fallback has a value`() {
        PlatformFileSystem.githubToken = null

        assertEquals("", resolveGitHttpsToken("git_https_token_myGraphId"))
    }

    @Test
    fun `resolveGitHttpsToken returns the legacy fallback when httpsTokenKey is null`() {
        PlatformFileSystem.githubToken = "ghp_legacy"

        assertEquals("ghp_legacy", resolveGitHttpsToken(null))
    }
}
