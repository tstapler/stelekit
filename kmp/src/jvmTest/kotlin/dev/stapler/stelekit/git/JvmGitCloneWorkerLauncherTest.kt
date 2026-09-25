// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.testsupport.cloningStub
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests [JvmGitCloneWorkerLauncher] (git-sync-resilience Story 3.1.3, Task 3.1.3c) — Desktop has
 * no foreground-service equivalent (out of scope per requirements), so `launchClone()` must call
 * [GitRepository.clone] directly and return its `Either` unchanged, not translate/wrap it.
 */
class JvmGitCloneWorkerLauncherTest {

    @Test
    fun `launchClone() calls GitRepository clone() directly and returns its Either result unchanged`() = runTest {
        var receivedUrl: String? = null
        var receivedLocalPath: String? = null
        var receivedAuth: GitAuth? = null
        var progressCalls = mutableListOf<String>()

        val fakeRepo = cloningStub { url, localPath, auth, onProgress ->
            receivedUrl = url
            receivedLocalPath = localPath
            receivedAuth = auth
            onProgress("Receiving objects")
            Unit.right()
        }
        val launcher = JvmGitCloneWorkerLauncher(fakeRepo)

        val result = launcher.launchClone(
            graphId = "unused-on-desktop",
            url = "https://example.invalid/graph.git",
            localPath = "/tmp/graph",
            auth = GitAuth.None,
        ) { progressCalls += it }

        assertEquals("https://example.invalid/graph.git", receivedUrl)
        assertEquals("/tmp/graph", receivedLocalPath)
        assertEquals(GitAuth.None, receivedAuth)
        assertEquals(listOf("Receiving objects"), progressCalls)
        assertIs<Either.Right<Unit>>(result)
    }

    @Test
    fun `launchClone() returns the Left GitRepository clone() produces unchanged, not translated`() = runTest {
        val expectedError = DomainError.GitError.CloneFailed("boom")
        val fakeRepo = cloningStub { _, _, _, _ -> expectedError.left() }
        val launcher = JvmGitCloneWorkerLauncher(fakeRepo)

        val result = launcher.launchClone("graph-id", "https://example.invalid/graph.git", "/tmp/graph", GitAuth.None) {}

        val error = assertIs<Either.Left<DomainError.GitError>>(result)
        assertEquals(expectedError, error.value)
    }
}
