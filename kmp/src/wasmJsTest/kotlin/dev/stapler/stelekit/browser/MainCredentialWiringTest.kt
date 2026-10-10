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

    // Every @Test in this file must be `= runTest { ... }`, even ones exercising only the
    // synchronous resolveGitHttpsToken() — a plain (non-runTest) @Test doesn't let the wasmJs
    // test bridge properly await this suspend @BeforeTest's CredentialStore.preload() call before
    // starting the test body. The orphaned preload() coroutine then keeps running in the
    // background and can still be mid-flight (writing a fresh key to real localStorage) when the
    // *next test class* in the suite starts, causing spurious cross-class localStorage-state
    // failures. Found via sdd:6-verify when this file's non-runTest tests broke
    // CredentialStoreTest's "preload generates a key on first run against empty localStorage"
    // assertion in the full wasmJsBrowserTest run.
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
    fun `resolveGitHttpsToken falls back to PlatformFileSystem githubToken when CredentialStore has no value yet`() = runTest {
        PlatformFileSystem.githubToken = "ghp_legacy"

        assertEquals("ghp_legacy", resolveGitHttpsToken("git_https_token_myGraphId"))
    }

    @Test
    fun `resolveGitHttpsToken returns empty string when neither CredentialStore nor the legacy fallback has a value`() = runTest {
        PlatformFileSystem.githubToken = null

        assertEquals("", resolveGitHttpsToken("git_https_token_myGraphId"))
    }

    @Test
    fun `resolveGitHttpsToken returns the legacy fallback when httpsTokenKey is null`() = runTest {
        PlatformFileSystem.githubToken = "ghp_legacy"

        assertEquals("ghp_legacy", resolveGitHttpsToken(null))
    }
}
