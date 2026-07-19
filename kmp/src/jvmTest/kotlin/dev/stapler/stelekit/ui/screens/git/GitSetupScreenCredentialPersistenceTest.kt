// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.screens.git

import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.platform.PlatformSettings
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
