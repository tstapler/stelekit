// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.screens.git

import dev.stapler.stelekit.git.GitCredentialConnectionStore
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PR #239 review Finding 1 (BLOCKER) regression: `browser/Main.kt`'s `configResolver` reads git
 * credentials from `PlatformSettings` ("githubOwner"/"githubRepo"/"githubBranch"/"githubToken"),
 * but nothing in [GitSetupScreen]'s save flow ever wrote them there — `CredentialStore` is a
 * separate, wasmJs-no-op mechanism. [persistWebGitCredentials] is the extracted, directly
 * testable function that closes this gap; these tests prove it populates the exact keys
 * `configResolver` reads, and that it safely no-ops instead of overwriting existing settings with
 * blanks when the inputs don't support persisting a credential.
 *
 * Redirects `user.home` to an isolated temp directory for the duration of the test, following the
 * pattern in `PlatformSettingsContainsKeyTest`, since the JVM `PlatformSettings` actual is backed
 * by `~/.stelekit/prefs.properties`.
 */
class GitSetupScreenCredentialPersistenceTest {

    private lateinit var originalUserHome: String
    private lateinit var tempHome: java.io.File

    @BeforeTest
    fun setUp() {
        originalUserHome = System.getProperty("user.home")
        tempHome = createTempDirectory("stelekit_git_setup_credential_test_").toFile()
        System.setProperty("user.home", tempHome.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", originalUserHome)
        tempHome.deleteRecursively()
    }

    @Test
    fun `persistWebGitCredentials writes owner repo branch and token for a parseable HTTPS GitHub URL`() {
        persistWebGitCredentials(
            cloneUrl = "https://github.com/tstapler/steno-wiki.git",
            branch = "main",
            authType = GitAuthType.HTTPS_TOKEN,
            token = "ghp_abc123",
        )

        val settings = PlatformSettings()
        assertEquals("tstapler", settings.getString("githubOwner", ""))
        assertEquals("steno-wiki", settings.getString("githubRepo", ""))
        assertEquals("main", settings.getString("githubBranch", ""))
        assertEquals("ghp_abc123", settings.getString("githubToken", ""))
    }

    @Test
    fun `persistWebGitCredentials parses owner and repo from a parseable GitLab URL`() {
        persistWebGitCredentials(
            cloneUrl = "https://gitlab.com/tstapler-notes/wiki.git",
            branch = "develop",
            authType = GitAuthType.HTTPS_TOKEN,
            token = "glpat-xyz789",
        )

        val settings = PlatformSettings()
        assertEquals("tstapler-notes", settings.getString("githubOwner", ""))
        assertEquals("wiki", settings.getString("githubRepo", ""))
        assertEquals("develop", settings.getString("githubBranch", ""))
        assertEquals("glpat-xyz789", settings.getString("githubToken", ""))
    }

    @Test
    fun `persistWebGitCredentials does not overwrite existing settings when authType is not HTTPS_TOKEN`() {
        val settings = PlatformSettings()
        settings.putString("githubOwner", "existing-owner")
        settings.putString("githubToken", "existing-token")

        persistWebGitCredentials(
            cloneUrl = "https://github.com/someone/else.git",
            branch = "main",
            authType = GitAuthType.SSH_KEY,
            token = "irrelevant",
        )

        assertEquals("existing-owner", PlatformSettings().getString("githubOwner", ""))
        assertEquals("existing-token", PlatformSettings().getString("githubToken", ""))
    }

    @Test
    fun `persistWebGitCredentials does not overwrite existing settings when cloneUrl is blank`() {
        val settings = PlatformSettings()
        settings.putString("githubOwner", "existing-owner")

        persistWebGitCredentials(
            cloneUrl = "",
            branch = "main",
            authType = GitAuthType.HTTPS_TOKEN,
            token = "some-token",
        )

        assertEquals("existing-owner", PlatformSettings().getString("githubOwner", ""))
    }

    @Test
    fun `persistWebGitCredentials does not overwrite existing settings when token is blank`() {
        val settings = PlatformSettings()
        settings.putString("githubToken", "existing-token")

        persistWebGitCredentials(
            cloneUrl = "https://github.com/tstapler/steno-wiki.git",
            branch = "main",
            authType = GitAuthType.HTTPS_TOKEN,
            token = "",
        )

        assertEquals("existing-token", PlatformSettings().getString("githubToken", ""))
    }

    @Test
    fun `persistWebGitCredentials no-ops when cloneUrl is unparseable`() {
        val settings = PlatformSettings()
        settings.putString("githubOwner", "existing-owner")

        persistWebGitCredentials(
            cloneUrl = "not-a-valid-remote-url",
            branch = "main",
            authType = GitAuthType.HTTPS_TOKEN,
            token = "some-token",
        )

        assertEquals("existing-owner", PlatformSettings().getString("githubOwner", ""))
    }

    // ── resolveHttpsTokenKey / resolveOauthTokenKey (credential-connection reuse) ──────────────

    @Test
    fun `resolveHttpsTokenKey stores under the graph-scoped key and remembers a new manually-entered token as a connection`() {
        val credentialStore = CredentialStore()
        val connectionStore = GitCredentialConnectionStore(PlatformSettings(), credentialStore)

        val key = resolveHttpsTokenKey(
            graphId = "graph-1",
            httpsToken = "ghp_abc123",
            cloneUrl = "https://github.com/tstapler/steno-wiki.git",
            selectedConnectionId = null,
            connectionStore = connectionStore,
            credentialStore = credentialStore,
            fallbackKey = null,
        )

        assertEquals("git_https_token_graph-1", key)
        assertEquals("ghp_abc123", credentialStore.retrieve(key!!))
        val saved = connectionStore.listConnections(GitAuthType.HTTPS_TOKEN).singleOrNull()
        assertEquals("ghp_abc123", saved?.let { connectionStore.getSecret(it) }, "must be remembered for a future graph")
        assertEquals("github.com", saved?.host)
    }

    @Test
    fun `resolveHttpsTokenKey does not duplicate a connection when the token came from picking a saved one`() {
        val credentialStore = CredentialStore()
        val connectionStore = GitCredentialConnectionStore(PlatformSettings(), credentialStore)
        val existing = connectionStore.saveConnection(
            host = "github.com",
            accountLabel = "github.com token",
            authType = GitAuthType.HTTPS_TOKEN,
            secret = "ghp_reused",
            createdAt = 1L,
        )

        resolveHttpsTokenKey(
            graphId = "graph-2",
            httpsToken = "ghp_reused",
            cloneUrl = "",
            selectedConnectionId = existing.id,
            connectionStore = connectionStore,
            credentialStore = credentialStore,
            fallbackKey = null,
        )

        assertEquals(1, connectionStore.listConnections(GitAuthType.HTTPS_TOKEN).size, "picking a saved connection must not create a second one")
    }

    @Test
    fun `resolveHttpsTokenKey returns fallbackKey when the token field is blank`() {
        val credentialStore = CredentialStore()
        val connectionStore = GitCredentialConnectionStore(PlatformSettings(), credentialStore)

        val key = resolveHttpsTokenKey(
            graphId = "graph-3",
            httpsToken = "",
            cloneUrl = "",
            selectedConnectionId = null,
            connectionStore = connectionStore,
            credentialStore = credentialStore,
            fallbackKey = "git_https_token_graph-3-existing",
        )

        assertEquals("git_https_token_graph-3-existing", key)
        assertTrue(connectionStore.listConnections(GitAuthType.HTTPS_TOKEN).isEmpty())
    }

    @Test
    fun `resolveOauthTokenKey copies a saved connection's secret into the graph-scoped key`() {
        val credentialStore = CredentialStore()
        val connectionStore = GitCredentialConnectionStore(PlatformSettings(), credentialStore)
        val existing = connectionStore.saveConnection(
            host = "github.com",
            accountLabel = "tstapler",
            authType = GitAuthType.GITHUB_OAUTH,
            secret = "gho_saved",
            createdAt = 1L,
        )

        val key = resolveOauthTokenKey(
            graphId = "graph-4",
            selectedConnectionId = existing.id,
            connectionStore = connectionStore,
            credentialStore = credentialStore,
            fallbackKey = "git_github_oauth_graph-4",
        )

        assertEquals("git_github_oauth_graph-4", key)
        assertEquals("gho_saved", credentialStore.retrieve(key!!), "the graph-scoped key is what every platform's auth code actually reads")
    }

    @Test
    fun `resolveOauthTokenKey falls back unchanged for a fresh device-flow completion`() {
        val credentialStore = CredentialStore()
        val connectionStore = GitCredentialConnectionStore(PlatformSettings(), credentialStore)
        // Simulates startOAuthFlow having already stored the token directly under the graph-scoped
        // key — no saved connection was selected (selectedConnectionId = null).
        credentialStore.store("git_github_oauth_graph-5", "gho_fresh")

        val key = resolveOauthTokenKey(
            graphId = "graph-5",
            selectedConnectionId = null,
            connectionStore = connectionStore,
            credentialStore = credentialStore,
            fallbackKey = "git_github_oauth_graph-5",
        )

        assertEquals("git_github_oauth_graph-5", key)
        assertEquals("gho_fresh", credentialStore.retrieve(key!!))
    }
}
