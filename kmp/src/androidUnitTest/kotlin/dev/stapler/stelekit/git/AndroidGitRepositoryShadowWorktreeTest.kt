// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import arrow.core.Either
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.FakeCredentialAccess
import dev.stapler.stelekit.git.testsupport.FakeSafFileSystem
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowStatFs

/**
 * Regression suite for [AndroidGitRepository] against a real Robolectric shadow-worktree
 * (`context.filesDir`), closing the zero-coverage gap flagged by finding #8 of
 * `project_plans/android-git-saf-shadow-worktree/implementation/plan.md` (Epic 8.2, Story 8.2.1).
 *
 * `pathResolver = { null }` on every constructed [AndroidGitRepository] forces shadow-mirror mode
 * to activate for every `saf://...` `repoRoot` (plan.md design decision #6 — no [GitShadowWorktree]
 * is ever passed in directly). [FakeSafFileSystem] stands in for the user's SAF folder.
 *
 * Robolectric's [ShadowStatFs] intercepts `android.os.StatFs` regardless of the actual host
 * filesystem's free space, defaulting to whatever the *real* underlying temp filesystem reports —
 * which can legitimately be below Task 6.2.1a's 200 MB shadow-storage threshold in a constrained
 * CI/sandbox environment. Every `init()`/`clone()` call below must register ample stats for its
 * worktree path first so this suite exercises the JGit ops it's actually testing, not the storage
 * guard (that's Story 8.2.3's job, in `AndroidGitRepositoryStorageGuardTest`).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidGitRepositoryShadowWorktreeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        ShadowStatFs.reset()
    }

    private fun newRepository(fileSystem: FakeSafFileSystem = FakeSafFileSystem()): AndroidGitRepository =
        AndroidGitRepository(
            context = context,
            sshKeyProvider = null,
            credentialAccess = FakeCredentialAccess(),
            pathResolver = { null },
            fileSystem = fileSystem,
        )

    private fun setTestIdentity(git: Git) {
        val cfg = git.repository.config
        cfg.setString("user", null, "name", "Stelekit Test")
        cfg.setString("user", null, "email", "stelekit-test@example.com")
        cfg.save()
    }

    /** Registers ample [ShadowStatFs] stats for [repoRoot]'s shadow worktree so the Task 6.2.1a storage guard never trips in this suite. */
    private fun registerAmpleStorage(repository: AndroidGitRepository, repoRoot: String) {
        val worktreePath = requireNotNull(repository.shadowWorktreeFor(repoRoot)).worktreeRootPath
        ShadowStatFs.registerStats(worktreePath, 2_000_000, 1_000_000, 1_000_000)
    }

    // ── Task 8.2.1a: init/stageSubdir/commit/status end to end ─────────────────────────────

    @Test
    fun `init stageSubdir commit and status succeed end to end against the shadow worktree`() = runTest {
        val fs = FakeSafFileSystem()
        val repoRoot = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Awiki-basic"
        val repository = newRepository(fs)

        // Pre-existing SAF content (as if the user already had a wiki page before enabling git
        // sync) — pulled into the shadow tree unconditionally by syncShadowAfterInitOrClone.
        fs.seed("$repoRoot/note.md", "- Hello\n")

        registerAmpleStorage(repository, repoRoot)
        val initResult = repository.init(repoRoot)
        assertTrue(initResult.isRight(), "init failed: $initResult")

        val realPath = repository.resolveForJGit(repoRoot)
        Git.open(File(realPath)).use { git -> setTestIdentity(git) }

        val config = GitConfig(
            graphId = "wiki-basic",
            repoRoot = repoRoot,
            wikiSubdir = null,
            authType = GitAuthType.NONE,
        )

        val stageResult = repository.stageSubdir(config)
        assertTrue(stageResult.isRight(), "stageSubdir failed: $stageResult")

        val commitResult = repository.commit(config, "Initial commit")
        assertTrue(commitResult.isRight(), "commit failed: $commitResult")

        val statusResult = repository.status(config)
        assertTrue(statusResult.isRight(), "status failed: $statusResult")
        val status = (statusResult as Either.Right).value
        assertFalse(status.hasLocalChanges, "expected no local changes immediately after commit, got $status")
    }

    // ── Tasks 8.2.1b/8.2.1c: merge-conflict path mapping + checkoutFile round trip ─────────

    @Test
    fun `merge conflict reports SAF-facing conflict paths and checkoutFile round trips via the fake SAF provider`() =
        runTest {
            val fs = FakeSafFileSystem()
            val repoRoot = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Awiki-merge"
            val repository = newRepository(fs)

            fs.seed("$repoRoot/conflict.md", "base\n")

            registerAmpleStorage(repository, repoRoot)
            val initResult = repository.init(repoRoot)
            assertTrue(initResult.isRight(), "init failed: $initResult")

            val shadowPath = repository.resolveForJGit(repoRoot)
            Git.open(File(shadowPath)).use { git -> setTestIdentity(git) }

            // Discover the actual initial branch name rather than assuming "main"/"master" — JGit's
            // default depends on the running environment's git config.
            val branchName = Git.open(File(shadowPath)).use { it.repository.branch } ?: "main"

            val config = GitConfig(
                graphId = "wiki-merge",
                repoRoot = repoRoot,
                wikiSubdir = null,
                remoteBranch = branchName,
                authType = GitAuthType.NONE,
            )

            assertTrue(repository.stageSubdir(config).isRight())
            assertTrue(repository.commit(config, "base commit").isRight())

            // "origin": a bare clone of the shadow repo at the base commit.
            val originDir = createTempDirectory("stelekit_git_origin_").toFile()
            Git.cloneRepository().setURI(shadowPath).setBare(true).setDirectory(originDir).call().close()

            Git.open(File(shadowPath)).use { git ->
                git.remoteAdd().setName("origin").setUri(URIish(originDir.absolutePath)).call()
            }

            // Remote-side divergent commit: clone origin into a scratch working dir, change the
            // same file differently, and push back.
            val originWorkDir = createTempDirectory("stelekit_git_origin_work_").toFile()
            Git.cloneRepository().setURI(originDir.absolutePath).setDirectory(originWorkDir).call().use { originGit ->
                setTestIdentity(originGit)
                File(originWorkDir, "conflict.md").writeText("remote version\n")
                originGit.add().addFilepattern(".").call()
                originGit.commit().setMessage("remote change").call()
                originGit.push().call()
            }

            // Local-side divergent commit, driven through the fake SAF provider (mirrors a real
            // edit made through the app), not by touching the shadow tree directly.
            fs.seed("$repoRoot/conflict.md", "local version\n")
            assertTrue(repository.stageSubdir(config).isRight())
            assertTrue(repository.commit(config, "local change").isRight())

            val fetchResult = repository.fetch(config)
            assertTrue(fetchResult.isRight(), "fetch failed: $fetchResult")

            val mergeResult = repository.merge(config)
            assertTrue(mergeResult.isRight(), "merge failed: $mergeResult")
            val merge = (mergeResult as Either.Right).value
            assertTrue(merge.hasConflicts, "expected a conflict from two divergent edits to the same file")
            assertEquals(1, merge.conflicts.size)

            val conflict = merge.conflicts.single()
            // Task 8.2.1b: this is the exact bug class fixed in commit 61b689fa61 — the conflict's
            // filePath must be SAF-facing, never a shadow-absolute path.
            assertTrue(
                conflict.filePath.startsWith("saf://"),
                "ConflictFile.filePath must be SAF-facing, got: ${conflict.filePath}",
            )
            assertFalse(
                conflict.filePath.contains("/gitshadow/"),
                "ConflictFile.filePath must never be a shadow-absolute path, got: ${conflict.filePath}",
            )
            assertEquals("$repoRoot/conflict.md", conflict.filePath)

            // Task 8.2.1c: resolve via checkoutFile(LOCAL) and confirm the fake SAF provider's
            // content was updated to match what was checked out.
            val checkoutResult = repository.checkoutFile(config, conflict.filePath, MergeSide.LOCAL)
            assertTrue(checkoutResult.isRight(), "checkoutFile failed: $checkoutResult")
            assertEquals("local version\n", fs.readFile(conflict.filePath))
        }

    // ── Task 8.2.1d: literal regression guard for the original resolveForJGit bug ──────────

    @Test
    fun `resolveForJGit never returns unresolvable saf scheme string when shadow worktree is active`() = runTest {
        val fs = FakeSafFileSystem()
        val repoRoot = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Awiki-resolve"
        val repository = newRepository(fs)

        registerAmpleStorage(repository, repoRoot)
        assertTrue(repository.init(repoRoot).isRight())

        val worktree = repository.shadowWorktreeFor(repoRoot)
        assertNotNull(worktree, "shadow-mirror mode must be active when pathResolver returns null for a saf:// root")

        val resolved = repository.resolveForJGit(repoRoot)
        assertFalse(
            resolved.startsWith("saf://"),
            "resolveForJGit() must never fall through to the raw unresolvable saf:// string once a " +
                "shadow worktree is active, got: $resolved",
        )
        assertEquals(worktree.worktreeRootPath, resolved)
    }
}
