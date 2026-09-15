// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.testsupport.FakeCredentialAccess
import dev.stapler.stelekit.git.testsupport.FakeSafFileSystem
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

/**
 * Coverage for the `StatFs`-based pre-clone storage guard (plan.md Task 6.2.1a,
 * `AndroidGitRepository.insufficientShadowStorageError`), closing validation.md Gap #3: neither
 * the error path nor the happy path had any test anywhere in the original Phase 8 test list.
 *
 * Faking [android.os.StatFs] uses Robolectric's [ShadowStatFs] — confirmed against the actual
 * `shadows-framework:4.16` jar (the version this project pins) via `javap`, since no test in this
 * codebase used it before this file:
 * `ShadowStatFs.registerStats(path: String, totalBlocks: Int, freeBlocks: Int, availableBlocks: Int)`,
 * with `ShadowStatFs.BLOCK_SIZE == 4096`. `availableBytes` is therefore `availableBlocks * 4096`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidGitRepositoryStorageGuardTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        ShadowStatFs.reset()
    }

    private fun newRepository(): AndroidGitRepository =
        AndroidGitRepository(
            context = context,
            sshKeyProvider = null,
            credentialAccess = FakeCredentialAccess(),
            pathResolver = { null },
            fileSystem = FakeSafFileSystem(),
        )

    private fun setTestIdentity(git: Git) {
        val cfg = git.repository.config
        cfg.setString("user", null, "name", "Stelekit Test")
        cfg.setString("user", null, "email", "stelekit-test@example.com")
        cfg.save()
    }

    // ── Task 8.2.3a: insufficient-storage error path ────────────────────────────────────────

    @Test
    fun `clone returns WorkingTreeSyncFailed and never invokes JGit when available shadow storage is below threshold`() =
        runTest {
            val repository = newRepository()
            val repoRoot = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Alow-storage"

            // Precompute the exact path AndroidGitRepository.clone() will StatFs() against — same
            // cached GitShadowWorktree instance, same key derivation.
            val worktreePath = requireNotNull(repository.shadowWorktreeFor(repoRoot)).worktreeRootPath

            // 10 blocks * 4096 bytes = 40 960 bytes available — far below the 200 MB threshold.
            ShadowStatFs.registerStats(worktreePath, 1_000, 10, 10)

            val result = repository.clone(
                url = "/should-never-be-reached",
                localPath = repoRoot,
                auth = GitAuth.None,
                onProgress = {},
            )

            assertTrue(result.isLeft(), "expected clone to fail fast on insufficient storage, got: $result")
            val error = (result as Either.Left).value
            assertIs<DomainError.GitError.WorkingTreeSyncFailed>(error)
            assertEquals("clone", error.direction)

            assertFalse(
                File(worktreePath, ".git").exists(),
                "the storage guard must run before Git.cloneRepository(), not merely make JGit fail for an unrelated reason",
            )
        }

    // ── Task 8.2.3b: sufficient-storage happy path ───────────────────────────────────────────

    @Test
    fun `clone proceeds unaffected by the storage guard when available shadow storage is ample`() = runTest {
        val originDir = createTempDirectory("stelekit_storage_guard_origin_").toFile()
        Git.init().setDirectory(originDir).call().use { git ->
            setTestIdentity(git)
            File(originDir, "note.md").writeText("- Hello\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("seed commit").call()
        }

        val repository = newRepository()
        val repoRoot = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Aample-storage"

        val worktreePath = requireNotNull(repository.shadowWorktreeFor(repoRoot)).worktreeRootPath

        // 1 000 000 blocks * 4096 bytes ~= 3.9 GB available — comfortably above the 200 MB threshold.
        ShadowStatFs.registerStats(worktreePath, 2_000_000, 1_000_000, 1_000_000)

        val result = repository.clone(
            url = originDir.absolutePath,
            localPath = repoRoot,
            auth = GitAuth.None,
            onProgress = {},
        )

        assertTrue(result.isRight(), "expected clone to succeed when storage is ample, got: $result")
        assertTrue(File(worktreePath, ".git").exists(), "expected a real .git directory after a successful clone")
    }
}
