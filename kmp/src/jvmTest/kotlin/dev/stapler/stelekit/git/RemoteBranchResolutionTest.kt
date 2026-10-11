// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.BareOriginFixtures
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression for the silent sync no-op: a configured `remoteBranch` that does not exist on the
 * remote (stored `main`, remote only has `master`) used to make `fetch` return
 * `Right(FetchResult(false, 0))` and sync report green success while pulling nothing.
 */
class RemoteBranchResolutionTest {

    private val temps = mutableListOf<File>()
    private val repository = JvmGitRepository()

    @AfterTest
    fun tearDown() {
        temps.forEach { it.deleteRecursively() }
    }

    private fun tracked(f: File): File = f.also { temps += it }

    private suspend fun cloneOf(origin: File, remoteBranch: String): GitConfig {
        val dest = tracked(createTempDirectory("stelekit_clone_").toFile())
        val dir = File(dest, "repo")
        val result = repository.clone(origin.absolutePath, dir.absolutePath, GitAuth.None, onProgress = {})
        assertTrue(result.isRight(), "clone failed: $result")
        Git.open(dir).use { BareOriginFixtures.setIdentity(it) }
        return GitConfig(
            graphId = "g",
            repoRoot = dir.absolutePath,
            wikiSubdir = null,
            remoteBranch = remoteBranch,
            authType = GitAuthType.NONE,
        )
    }

    private fun origin(branch: String, seed: Boolean = true): File =
        tracked(BareOriginFixtures.createBareOrigin("stelekit_origin_", branch, seed))

    @Test
    fun `fetch with a configured branch missing on the remote returns RemoteBranchNotFound`() = runTest {
        val config = cloneOf(origin("master"), remoteBranch = "main")

        val result = repository.fetch(config)

        val error = (result as? Either.Left)?.value
        assertEquals(
            DomainError.GitError.RemoteBranchNotFound(remote = "origin", branch = "main", available = listOf("master")),
            error,
            "expected typed error, got $result",
        )
        assertEquals("Branch 'main' not found on remote — tap to fix", error?.message)
    }

    @Test
    fun `merge with a configured branch missing on the remote returns RemoteBranchNotFound`() = runTest {
        val config = cloneOf(origin("master"), remoteBranch = "main")

        val result = repository.merge(config)

        assertTrue(
            (result as? Either.Left)?.value is DomainError.GitError.RemoteBranchNotFound,
            "expected RemoteBranchNotFound, got $result",
        )
    }

    @Test
    fun `fetch then merge with the correct branch pulls a remote commit`() = runTest {
        val origin = origin("master")
        val config = cloneOf(origin, remoteBranch = "master")
        BareOriginFixtures.pushCommit(origin, "master", "logseq/journals/2026_10_10.md", "- hi\n", "add journal")

        val fetch = (repository.fetch(config) as Either.Right).value
        assertTrue(fetch.hasRemoteChanges)
        assertEquals(1, fetch.remoteCommitCount)

        val merge = (repository.merge(config) as Either.Right).value
        assertFalse(merge.hasConflicts)
        assertEquals(1, merge.mergedCommitCount)
        assertTrue(File(config.repoRoot, "logseq/journals/2026_10_10.md").exists())
    }

    @Test
    fun `fetch does not report changes when local is only ahead`() = runTest {
        val config = cloneOf(origin("master"), remoteBranch = "master")
        File(config.repoRoot, "local.md").writeText("local\n")
        Git.open(File(config.repoRoot)).use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("local only").call()
        }

        val fetch = (repository.fetch(config) as Either.Right).value

        assertFalse(fetch.hasRemoteChanges, "local-ahead must not count as remote changes")
    }

    @Test
    fun `a stale remote-tracking ref is removed after the remote renames the branch`() = runTest {
        val origin = origin("master")
        val config = cloneOf(origin, remoteBranch = "master")
        BareOriginFixtures.renameBranch(origin, from = "master", to = "main")

        val error = (repository.fetch(config) as? Either.Left)?.value

        assertEquals(
            DomainError.GitError.RemoteBranchNotFound("origin", "master", listOf("main")),
            error,
        )
    }

    @Test
    fun `fetch against an empty remote returns RemoteEmpty`() = runTest {
        val empty = origin("main", seed = false)
        val dir = tracked(createTempDirectory("stelekit_empty_local_").toFile())
        Git.init().setDirectory(dir).setInitialBranch("main").call().use { git ->
            git.remoteAdd().setName("origin").setUri(org.eclipse.jgit.transport.URIish(empty.absolutePath)).call()
        }
        val config = GitConfig("g", dir.absolutePath, null, remoteBranch = "main", authType = GitAuthType.NONE)

        val result = repository.fetch(config)

        assertEquals(DomainError.GitError.RemoteEmpty, (result as? Either.Left)?.value)
    }

    @Test
    fun `fetch with a malformed branch name returns InvalidRefName`() = runTest {
        val config = cloneOf(origin("master"), remoteBranch = "master").copy(remoteBranch = "a..b")

        val result = repository.fetch(config)

        assertEquals(DomainError.GitError.InvalidRefName("a..b"), (result as? Either.Left)?.value)
    }

    @Test
    fun `push uses an explicit refspec and creates no stray branch on the remote`() = runTest {
        val origin = origin("master")
        val config = cloneOf(origin, remoteBranch = "master")
        Git.open(File(config.repoRoot)).use { git ->
            // A leftover local `main` (e.g. from an old `git init` default) must not be pushed.
            git.branchCreate().setName("main").call()
            File(config.repoRoot, "local.md").writeText("local\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("local commit").call()
        }

        val result = repository.push(config)

        assertTrue(result.isRight(), "push failed: $result")
        assertEquals(listOf("master"), BareOriginFixtures.remoteBranches(origin))
        val localHead = Git.open(File(config.repoRoot)).use { it.repository.resolve("HEAD").name }
        assertEquals(localHead, BareOriginFixtures.remoteHead(origin, "master"))
    }

    @Test
    fun `push sends the current local branch to the configured remote branch name`() = runTest {
        val origin = origin("master")
        val config = cloneOf(origin, remoteBranch = "master")
        Git.open(File(config.repoRoot)).use { git ->
            // Local branch name differs from the remote one and has no upstream config.
            git.branchRename().setOldName("master").setNewName("work").call()
            git.repository.config.unsetSection("branch", "work")
            git.repository.config.save()
            File(config.repoRoot, "local.md").writeText("local\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("local commit").call()
        }

        val result = repository.push(config)

        assertTrue(result.isRight(), "push failed: $result")
        assertEquals(listOf("master"), BareOriginFixtures.remoteBranches(origin))
        val localHead = Git.open(File(config.repoRoot)).use { it.repository.resolve("HEAD").name }
        assertEquals(localHead, BareOriginFixtures.remoteHead(origin, "master"))
    }
}
