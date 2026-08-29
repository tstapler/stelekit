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
}
