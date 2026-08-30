// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git

/**
 * Desktop non-regression smoke test (Phase 7, android-git-saf-shadow-worktree plan) — exercises
 * [JvmGitRepository] end to end against a real temp-dir repo. `kmp/src/jvmTest` had zero coverage
 * of the Desktop git implementation itself (only credential/device-flow helpers around it); this
 * closes that gap while proving Desktop's plain-`repoRoot` git path is unaffected by the
 * Android SAF shadow-worktree work landing in parallel — `JvmGitRepository` has no shadow/mapper
 * concept at all, by construction, so `config.repoRoot` is used as a real filesystem path
 * throughout.
 */
class JvmGitRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var repository: JvmGitRepository
    private lateinit var config: GitConfig

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("stelekit_jvm_git_repo_test_").toFile()
        repository = JvmGitRepository()
        config = GitConfig(
            graphId = "test-graph",
            repoRoot = tempDir.absolutePath,
            wikiSubdir = null,
            authType = GitAuthType.NONE,
        )
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `commit followed by status shows no local changes and log returns one entry`() = runTest {
        val initResult = repository.init(config.repoRoot)
        assertTrue(initResult.isRight(), "init failed: $initResult")

        // JvmGitRepository never sets an author/committer itself — it delegates to JGit's default
        // PersonIdent resolution (repo config, falling back to system properties). Set a
        // repo-local identity explicitly so this test is hermetic regardless of the running
        // machine's ~/.gitconfig.
        Git.open(File(config.repoRoot)).use { git ->
            val storedConfig = git.repository.config
            storedConfig.setString("user", null, "name", "Stelekit Test")
            storedConfig.setString("user", null, "email", "stelekit-test@example.com")
            storedConfig.save()
        }

        File(config.repoRoot, "journal.md").writeText("# Test journal entry\n")

        val stageResult = repository.stageSubdir(config)
        assertTrue(stageResult.isRight(), "stageSubdir failed: $stageResult")

        val commitResult = repository.commit(config, "Initial commit")
        assertTrue(commitResult.isRight(), "commit failed: $commitResult")

        val statusResult = repository.status(config)
        assertTrue(statusResult.isRight(), "status failed: $statusResult")
        val status = (statusResult as Either.Right).value
        assertFalse(status.hasLocalChanges, "expected no local changes immediately after commit, got $status")

        val logResult = repository.log(config, maxCount = 10)
        assertTrue(logResult.isRight(), "log failed: $logResult")
        val commits = (logResult as Either.Right).value
        assertEquals(1, commits.size, "expected exactly one commit in the log")
        assertEquals("Initial commit", commits.single().shortMessage)

        // config.repoRoot is a plain real filesystem path throughout -- no shadow/mapper
        // indirection exists on Desktop (that's an Android-only concept in this project).
        assertEquals(tempDir.absolutePath, config.repoRoot)
    }

    private fun setIdentity(git: Git) {
        val storedConfig = git.repository.config
        storedConfig.setString("user", null, "name", "Stelekit Test")
        storedConfig.setString("user", null, "email", "stelekit-test@example.com")
        storedConfig.save()
    }

    /**
     * merge() must parse the real conflict-marker content JGit wrote directly into the working
     * tree into hunks (via [ConflictResolver.parseConflictFile]) for line-level resolution,
     * instead of always shipping an empty hunk list — the pre-existing gap the conflict-resolution
     * project closes. `JvmGitRepositoryTest` had zero merge-conflict coverage at all before this.
     */
    @Test
    fun `merge conflict reports real conflict marker content parsed into hunks`() = runTest {
        assertTrue(repository.init(config.repoRoot).isRight())
        Git.open(File(config.repoRoot)).use { git -> setIdentity(git) }
        val baseBranch = Git.open(File(config.repoRoot)).use { it.repository.branch }
        val mergeConfig = config.copy(remoteBranch = baseBranch)

        File(config.repoRoot, "conflict.md").writeText("base\n")
        assertTrue(repository.stageSubdir(mergeConfig).isRight())
        assertTrue(repository.commit(mergeConfig, "base commit").isRight())

        val originDir = createTempDirectory("stelekit_jvm_git_merge_origin_").toFile()
        Git.cloneRepository().setURI(config.repoRoot).setBare(true).setDirectory(originDir).call().close()
        Git.open(File(config.repoRoot)).use { git ->
            git.remoteAdd().setName("origin")
                .setUri(org.eclipse.jgit.transport.URIish(originDir.absolutePath))
                .call()
        }

        val originWorkDir = createTempDirectory("stelekit_jvm_git_merge_origin_work_").toFile()
        Git.cloneRepository().setURI(originDir.absolutePath).setDirectory(originWorkDir).call().use { originGit ->
            setIdentity(originGit)
            File(originWorkDir, "conflict.md").writeText("remote version\n")
            originGit.add().addFilepattern(".").call()
            originGit.commit().setMessage("remote change").call()
            originGit.push().call()
        }

        File(config.repoRoot, "conflict.md").writeText("local version\n")
        assertTrue(repository.stageSubdir(mergeConfig).isRight())
        assertTrue(repository.commit(mergeConfig, "local change").isRight())

        assertTrue(repository.fetch(mergeConfig).isRight())
        val mergeResult = repository.merge(mergeConfig)
        assertTrue(mergeResult.isRight(), "merge failed: $mergeResult")
        val merge = (mergeResult as Either.Right).value
        assertTrue(merge.hasConflicts)
        assertEquals(1, merge.conflicts.size)

        val conflict = merge.conflicts.single()
        assertEquals(1, conflict.hunks.size, "expected exactly one conflicting hunk")
        val hunk = conflict.hunks.single()
        assertEquals(listOf("local version"), hunk.localLines)
        assertEquals(listOf("remote version"), hunk.remoteLines)
        assertTrue(
            conflict.rawContent?.contains("<<<<<<<") == true,
            "ConflictFile.rawContent must carry the real conflict-marker content, got: ${conflict.rawContent}",
        )
    }

    /**
     * Regression test for a real, pre-existing production bug this project's testing surfaced:
     * JGit 7.3.0's `ResetCommand` never implemented `ResetType.MERGE`/`KEEP` at all (confirmed by
     * disassembling `ResetCommand.class` — its mode switch implements only SOFT/MIXED/HARD and
     * unconditionally throws `UnsupportedOperationException` for MERGE/KEEP). `abortMerge()` used
     * `ResetType.MERGE` on both platforms, so calling it always threw — on every platform, with
     * zero prior test coverage anywhere in the suite. Fixed by switching to `ResetType.HARD`,
     * whose merge-state cleanup (`MERGE_HEAD`/`MERGE_MSG` removal, `RepositoryState` MERGING ->
     * SAFE) is unconditional on any `ResetType` other than SOFT, so it correctly aborts the
     * in-progress merge (empirically confirmed against a real conflicted merge before landing).
     */
    @Test
    fun `abortMerge resets a conflicted merge back to pre-merge HEAD content and clears merge state`() = runTest {
        assertTrue(repository.init(config.repoRoot).isRight())
        Git.open(File(config.repoRoot)).use { git -> setIdentity(git) }

        File(config.repoRoot, "shared.md").writeText("base\n")
        assertTrue(repository.stageSubdir(config).isRight())
        assertTrue(repository.commit(config, "base commit").isRight())

        val originDir = createTempDirectory("stelekit_jvm_git_origin_").toFile()
        Git.cloneRepository().setURI(config.repoRoot).setBare(true).setDirectory(originDir).call().close()
        Git.open(File(config.repoRoot)).use { git ->
            git.remoteAdd().setName("origin")
                .setUri(org.eclipse.jgit.transport.URIish(originDir.absolutePath))
                .call()
        }

        val originWorkDir = createTempDirectory("stelekit_jvm_git_origin_work_").toFile()
        Git.cloneRepository().setURI(originDir.absolutePath).setDirectory(originWorkDir).call().use { originGit ->
            setIdentity(originGit)
            File(originWorkDir, "shared.md").writeText("remote change\n")
            originGit.add().addFilepattern(".").call()
            originGit.commit().setMessage("remote change").call()
            originGit.push().call()
        }

        File(config.repoRoot, "shared.md").writeText("local change\n")
        assertTrue(repository.stageSubdir(config).isRight())
        assertTrue(repository.commit(config, "local change").isRight())

        assertTrue(repository.fetch(config).isRight())
        val mergeResult = repository.merge(config)
        assertTrue(mergeResult.isRight(), "merge failed: $mergeResult")
        assertTrue((mergeResult as Either.Right).value.hasConflicts, "expected a conflict")

        Git.open(File(config.repoRoot)).use { git ->
            assertEquals(
                org.eclipse.jgit.lib.RepositoryState.MERGING,
                git.repository.repositoryState,
                "expected MERGING state during the conflict",
            )
        }

        val abortResult = repository.abortMerge(config)
        assertTrue(abortResult.isRight(), "abortMerge failed: $abortResult")

        Git.open(File(config.repoRoot)).use { git ->
            assertEquals(
                org.eclipse.jgit.lib.RepositoryState.SAFE,
                git.repository.repositoryState,
                "expected merge state cleared after abortMerge",
            )
        }
        assertEquals(
            "local change\n",
            File(config.repoRoot, "shared.md").readText(),
            "expected working tree reset to pre-merge (local) HEAD content",
        )

        val statusResult = repository.status(config)
        assertTrue(statusResult.isRight(), "status failed: $statusResult")
        assertFalse(
            (statusResult as Either.Right).value.hasLocalChanges,
            "expected a clean working tree after abortMerge",
        )
    }
}
