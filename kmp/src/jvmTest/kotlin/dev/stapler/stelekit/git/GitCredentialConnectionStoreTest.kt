// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A saved [dev.stapler.stelekit.git.model.GitCredentialConnection] must survive independently of
 * any single graph — the whole point is letting a second graph reuse a PAT or OAuth connection
 * from an earlier one without re-entering it (see the credential-caching UX review this closes).
 *
 * Redirects `user.home` to an isolated temp directory, following
 * `GitSetupScreenCredentialPersistenceTest`'s pattern, since both `PlatformSettings` and
 * `CredentialStore` (JVM actuals) are file-backed under it.
 */
class GitCredentialConnectionStoreTest {

    private lateinit var originalUserHome: String
    private lateinit var tempHome: java.io.File

    @BeforeTest
    fun setUp() {
        originalUserHome = System.getProperty("user.home")
        tempHome = createTempDirectory("stelekit_git_connection_store_test_").toFile()
        System.setProperty("user.home", tempHome.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", originalUserHome)
        tempHome.deleteRecursively()
    }

    private fun freshStore() = GitCredentialConnectionStore(PlatformSettings(), CredentialStore())

    @Test
    fun `saveConnection persists metadata and secret independently of any graphId`() {
        val store = freshStore()

        val saved = store.saveConnection(
            host = "github.com",
            accountLabel = "tstapler",
            authType = GitAuthType.GITHUB_OAUTH,
            secret = "gho_abc123",
            createdAt = 1_700_000_000_000L,
        )

        // A fresh store instance (simulating a second graph's wizard, or an app restart) sees it.
        val reloaded = freshStore()
        val connections = reloaded.listConnections(GitAuthType.GITHUB_OAUTH)
        assertEquals(1, connections.size)
        assertEquals(saved.id, connections.single().id)
        assertEquals("tstapler", connections.single().accountLabel)
        assertEquals("gho_abc123", reloaded.getSecret(connections.single()))
    }

    @Test
    fun `listConnections filters by authType`() {
        val store = freshStore()
        store.saveConnection(
            host = "github.com",
            accountLabel = "tstapler",
            authType = GitAuthType.GITHUB_OAUTH,
            secret = "gho_abc123",
            createdAt = 1L,
        )
        store.saveConnection(
            host = "github.com",
            accountLabel = "work-pat",
            authType = GitAuthType.HTTPS_TOKEN,
            secret = "ghp_xyz789",
            createdAt = 2L,
        )

        assertEquals(1, store.listConnections(GitAuthType.GITHUB_OAUTH).size)
        assertEquals(1, store.listConnections(GitAuthType.HTTPS_TOKEN).size)
        assertTrue(store.listConnections(GitAuthType.SSH_KEY).isEmpty())
    }

    @Test
    fun `saveConnection with an existingId overwrites in place rather than duplicating`() {
        val store = freshStore()
        val first = store.saveConnection(
            host = "github.com",
            accountLabel = "tstapler",
            authType = GitAuthType.GITHUB_OAUTH,
            secret = "gho_old",
            createdAt = 1L,
        )

        store.saveConnection(
            host = "github.com",
            accountLabel = "tstapler",
            authType = GitAuthType.GITHUB_OAUTH,
            secret = "gho_refreshed",
            createdAt = 1L,
            existingId = first.id,
        )

        val connections = store.listConnections(GitAuthType.GITHUB_OAUTH)
        assertEquals(1, connections.size, "re-connecting the same account must not duplicate the entry")
        assertEquals("gho_refreshed", store.getSecret(connections.single()))
    }

    @Test
    fun `deleteConnection removes both metadata and secret`() {
        val store = freshStore()
        val saved = store.saveConnection(
            host = "github.com",
            accountLabel = "tstapler",
            authType = GitAuthType.HTTPS_TOKEN,
            secret = "ghp_abc123",
            createdAt = 1L,
        )

        store.deleteConnection(saved.id)

        assertTrue(store.listConnections(GitAuthType.HTTPS_TOKEN).isEmpty())
        assertNull(freshStore().getSecret(saved))
    }
}
