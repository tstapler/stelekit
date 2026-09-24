// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.screens.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.FetchResult
import dev.stapler.stelekit.git.GitAuth
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PR #351 review Finding A: [testGitConnection] is the user-visible fix for "Test connection
 * always failed for a new clone" (a new clone's `repoRoot` doesn't exist on disk yet, so
 * `fetch()`'s `Git.open()` always fails there — see the function's KDoc) and had zero coverage.
 * These tests pin the `CloneMode` branch: [dev.stapler.stelekit.ui.screens.git.CloneMode.CloneNewRepository]
 * must route through `testRemote()`, [dev.stapler.stelekit.ui.screens.git.CloneMode.UseExistingClone]
 * through `fetch()` — never the other one.
 */
class GitSetupScreenSaveLogicTest {

    private fun baseForm(cloneMode: CloneMode) = GitSetupFormSnapshot(
        graphId = "graph-1",
        cloneMode = cloneMode,
        cloneUrl = "https://example.com/repo.git",
        repoRoot = "/tmp/does-not-exist-yet",
        wikiSubdir = "",
        graphName = "",
        graphDescription = "",
        cloneStorageLocation = null,
        authType = GitAuthType.NONE,
        sshKeyPath = "",
        sshPassphrase = "",
        httpsToken = "",
        oauthConnectedAs = null,
        selectedHttpsConnectionId = null,
        selectedOauthConnectionId = null,
        remoteBranch = "main",
        pollIntervalMinutes = 15,
    )

    @Test
    fun `testGitConnection routes CloneNewRepository through testRemote not fetch`() = runTest {
        var testRemoteCalled = false
        val gitRepository = object : StubGitRepository() {
            override suspend fun testRemote(url: String, auth: GitAuth): Either<DomainError.GitError, Unit> {
                testRemoteCalled = true
                return Unit.right()
            }
            // fetch() is intentionally left unoverridden — the stub base throws if it's ever
            // called, which is exactly how this test proves fetch() was NOT invoked.
        }

        val result = testGitConnection(
            form = baseForm(CloneMode.CloneNewRepository),
            gitRepository = gitRepository,
            credentialStore = CredentialStore(),
        )

        assertTrue(result.isRight(), "expected testGitConnection to succeed: $result")
        assertTrue(testRemoteCalled, "CloneNewRepository must route through testRemote()")
    }

    @Test
    fun `testGitConnection routes UseExistingClone through fetch not testRemote`() = runTest {
        var fetchCalled = false
        val gitRepository = object : StubGitRepository() {
            override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
                fetchCalled = true
                return FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()
            }
            // testRemote() is intentionally left unoverridden — the stub base throws if it's ever
            // called, which is exactly how this test proves testRemote() was NOT invoked.
        }

        val result = testGitConnection(
            form = baseForm(CloneMode.UseExistingClone),
            gitRepository = gitRepository,
            credentialStore = CredentialStore(),
        )

        assertTrue(result.isRight(), "expected testGitConnection to succeed: $result")
        assertTrue(fetchCalled, "UseExistingClone must route through fetch()")
    }
}
