// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitCredentialConnection
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * web-credential-persistence (Epic 2.1, Story 2.1.2): proves the "reuse a saved connection" flow
 * works end-to-end on web against the real wasmJs [CredentialStore], not a fake — closing
 * requirements.md's success metric #2.
 */
class GitCredentialConnectionStoreWasmJsTest {

    @BeforeTest
    fun setUp() = runTest {
        CredentialStore.resetForTest()
        CredentialStore.preload()
    }

    @AfterTest
    fun tearDown() {
        CredentialStore.resetForTest()
    }

    @Test
    fun `getSecret returns null for a connection id that was never saved`() {
        val store = GitCredentialConnectionStore(PlatformSettings(), CredentialStore())
        val neverSaved = GitCredentialConnection(
            id = "never-saved-id",
            host = "github.com",
            accountLabel = "octocat",
            authType = GitAuthType.HTTPS_TOKEN,
            createdAt = 0L,
        )

        assertNull(store.getSecret(neverSaved))
    }

    @Test
    fun `saveConnection followed by getSecret on a second GitCredentialConnectionStore instance in the same session returns the original secret`() = runTest {
        val first = GitCredentialConnectionStore(PlatformSettings(), CredentialStore())
        val saved = first.saveConnection(
            host = "github.com",
            accountLabel = "octocat",
            authType = GitAuthType.HTTPS_TOKEN,
            secret = "ghp_reuseme",
            createdAt = 1L,
        )
        CredentialStore.flushForTest()

        val second = GitCredentialConnectionStore(PlatformSettings(), CredentialStore())
        assertEquals("ghp_reuseme", second.getSecret(saved))
    }

    @Test
    fun `deleteConnection removes the saved connection so a subsequent getSecret returns null`() = runTest {
        val store = GitCredentialConnectionStore(PlatformSettings(), CredentialStore())
        val saved = store.saveConnection(
            host = "gitlab.com",
            accountLabel = "tstapler",
            authType = GitAuthType.HTTPS_TOKEN,
            secret = "glpat-deleteme",
            createdAt = 2L,
        )
        CredentialStore.flushForTest()

        store.deleteConnection(saved.id)
        CredentialStore.flushForTest()

        assertNull(store.getSecret(saved))
    }
}
