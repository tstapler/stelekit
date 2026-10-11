// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.git.model.CloneDepthState
import dev.stapler.stelekit.git.model.DEFAULT_CLONE_DEPTH
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec

/**
 * git-sync-resilience Story 2.1.4 — [JvmGitRepository.unshallow] and [GitSyncService.deepen]
 * against real JGit fixtures (no mocks — see ADR-001: this is the "deepen" half of
 * checkpoint-by-depth resume).
 */
class GitRepositoryUnshallowTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(prefix: String) = createTempDirectory(prefix).toFile().also { tempDirs += it }

    private fun setIdentity(git: Git) {
        git.repository.config.apply {
            setString("user", null, "name", "Stelekit Test")
            setString("user", null, "email", "stelekit-test@example.com")
            save()
        }
    }

    /** A local bare repo with [commitCount] commits, plus a shallow clone of it at [destination]. */
    private fun shallowCloneFixture(commitCount: Int): Pair<File, File> {
        val bareOrigin = tempDir("stelekit_unshallow_origin_")
        Git.init().setBare(true).setDirectory(bareOrigin).setInitialBranch("main").call().close()
        val seedWorkDir = tempDir("stelekit_unshallow_seed_")
        Git.cloneRepository().setURI(bareOrigin.absolutePath).setDirectory(seedWorkDir).call().use { git ->
            setIdentity(git)
            repeat(commitCount) { i ->
                File(seedWorkDir, "journal.md").writeText("entry $i\n")
                git.add().addFilepattern(".").call()
                git.commit().setMessage("commit $i").call()
            }
            git.push().setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
        }

        val destination = tempDir("stelekit_unshallow_dest_")
        destination.delete()
        Git.cloneRepository()
            .setURI(bareOrigin.absolutePath)
            .setDirectory(destination)
            .setDepth(DEFAULT_CLONE_DEPTH)
            .call()
            .close()
        return bareOrigin to destination
    }

    private fun baseConfig(repoRoot: String) = GitConfig(
        graphId = "graph-unshallow",
        repoRoot = repoRoot,
        wikiSubdir = null,
        authType = GitAuthType.NONE,
        remoteName = "origin",
        remoteBranch = "main",
        cloneDepthState = CloneDepthState.Shallow(DEFAULT_CLONE_DEPTH),
    )

    @Test
    fun `unshallow() against a real local fixture remote widens a shallow clone to full history`() = runBlocking {
        val (_, destination) = shallowCloneFixture(DEFAULT_CLONE_DEPTH + 10)
        val config = baseConfig(destination.absolutePath)

        Git.open(destination).use { git ->
            assertTrue(
                git.repository.objectDatabase.shallowCommits.isNotEmpty(),
                "precondition: destination must be a shallow clone before unshallow()",
            )
        }

        val repository = JvmGitRepository()
        val result = repository.unshallow(config)
        assertIs<arrow.core.Either.Right<Unit>>(result, "unshallow() failed: $result")

        Git.open(destination).use { git ->
            assertTrue(
                git.repository.objectDatabase.shallowCommits.isEmpty(),
                "expected shallowCommits to be empty after a successful unshallow()",
            )
        }
    }

    /**
     * Task 2.1.4d's ref-divergence guard: the remote advances (new commits pushed) after the
     * shallow clone but before unshallow() runs — unshallow() must detect this via its ls-remote
     * comparison and fail closed instead of attempting a widen JGit might mishandle. The
     * repository must remain shallow (no widen was attempted).
     */
    @Test
    fun `unshallow() returns FetchFailed and never widens when the remote has diverged since the shallow clone`() = runBlocking {
        val (bareOrigin, destination) = shallowCloneFixture(DEFAULT_CLONE_DEPTH + 10)
        val config = baseConfig(destination.absolutePath)

        // Advance the remote after the clone, without the local clone ever re-fetching —
        // exactly the "diverged since the shallow clone" shape the guard exists for.
        val advanceWorkDir = tempDir("stelekit_unshallow_advance_")
        Git.cloneRepository().setURI(bareOrigin.absolutePath).setDirectory(advanceWorkDir).call().use { git ->
            setIdentity(git)
            File(advanceWorkDir, "journal.md").writeText("a diverging remote commit\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("remote advanced after the shallow clone").call()
            git.push().setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
        }

        val repository = JvmGitRepository()
        val result = repository.unshallow(config)
        assertIs<arrow.core.Either.Left<dev.stapler.stelekit.error.DomainError.GitError>>(result)
        assertIs<dev.stapler.stelekit.error.DomainError.GitError.FetchFailed>(result.value)
        assertTrue(
            result.value.message.contains("diverged", ignoreCase = true),
            "expected a divergence-shaped message, got: ${result.value.message}",
        )

        Git.open(destination).use { git ->
            assertTrue(
                git.repository.objectDatabase.shallowCommits.isNotEmpty(),
                "expected no widen attempt — the repo must still be shallow after a rejected unshallow()",
            )
        }
    }

    @Test
    fun `GitSyncService deepen widens a shallow clone and persists CloneDepthState FullHistory via GitConfigRepository`() = runBlocking {
        val (_, destination) = shallowCloneFixture(DEFAULT_CLONE_DEPTH + 10)
        val config = baseConfig(destination.absolutePath)

        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository(), scope = scope)
            val gitConfigRepository = SqlDelightGitConfigRepository(database, actor)
            val saveResult = gitConfigRepository.saveConfig(config)
            assertIs<arrow.core.Either.Right<Unit>>(saveResult)

            val gitSyncService = buildGitSyncTestService(
                gitRepository = JvmGitRepository(),
                configRepository = gitConfigRepository,
            )
            val deepenResult = gitSyncService.deepen(config.graphId)
            assertIs<arrow.core.Either.Right<Unit>>(deepenResult, "deepen() failed: $deepenResult")

            val readBack = gitConfigRepository.getConfig(config.graphId)
            assertIs<arrow.core.Either.Right<GitConfig?>>(readBack)
            assertEquals(CloneDepthState.FullHistory, readBack.value?.cloneDepthState)
        } finally {
            scope.cancel()
        }
    }
}
