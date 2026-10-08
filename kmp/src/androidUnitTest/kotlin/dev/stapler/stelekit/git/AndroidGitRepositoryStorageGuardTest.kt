// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.testsupport.FakeCredentialAccess
import dev.stapler.stelekit.git.testsupport.FakeSafFileSystem
import dev.stapler.stelekit.platform.FileSystem
import java.io.File
import java.net.SocketException
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

    private fun newRepository(fileSystem: FileSystem = FakeSafFileSystem()): AndroidGitRepository =
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
                onStateChange = {},
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
            onStateChange = {},
        )

        assertTrue(result.isRight(), "expected clone to succeed when storage is ample, got: $result")
        assertTrue(File(worktreePath, ".git").exists(), "expected a real .git directory after a successful clone")
    }

    // ── Story 6.1.4 (git-sync-resilience Epic 6.1): retry-vs-shadow-freshness regression ────────
    //
    // Rabbit Hole named in requirements.md: "retry/backoff interacting with the shadow-worktree
    // freshness precondition". `AndroidGitRepository.clone()` calls `Git.cloneRepository().call()`
    // then `shadow.syncShadowAfterInitOrClone(localPath, git)` (see AndroidGitRepository.kt:136-138)
    // — the two unit tests below drive `GitShadowWorktree` directly to prove `isFresh()`/
    // `ensureFresh()`'s behavior at exactly that interruption point (JGit content on disk, no
    // manifest written yet); the integration test drives the real `AndroidGitRepository.clone()`
    // end to end with an injected one-shot failure landing at that same point.

    // Full acceptance criterion (validation.md): "ensureFresh() does not report the shadow tree as
    // fresh based on stale/absent manifest state left by the failed first attempt, before the
    // retry completes." Test names below are kept shorter than that (not verbatim) because the
    // JVM derives this method's lambda/coroutine class names from its own name, and a long enough
    // name blows the filesystem's ~255-byte filename limit — surfacing as a misleading
    // "Permission denied" writing the generated .class file, not a length-related error (found
    // while writing GitCloneWorkerFastFailIntegrationTest's Story 6.1.3 test).
    @Test
    fun `ensureFresh reports NOT fresh for a shadow tree with content but no manifest yet (pre-retry interrupted state)`() = runTest {
        val worktree = GitShadowWorktree(context, "stelekit_freshness_regression_unit_error", "saf://root")
        // Simulates "Git.cloneRepository().call() succeeded but syncShadowAfterInitOrClone never
        // ran": real content lands on disk exactly as a completed JGit clone would leave it, but no
        // manifest is ever written (syncFromSafRoot is what writes it, and it never got to run).
        File(worktree.worktreeRootPath, "note.md").writeText("- pre-existing content\n")

        val safListing = listOf("note.md" to 5_000L)
        val isFreshBeforeRetry = worktree.isFresh(listRecursive = { safListing })

        assertFalse(
            isFreshBeforeRetry,
            "a shadow tree with real content but no manifest (the interrupted first attempt's exact state) must never be reported fresh",
        )
    }

    @Test
    fun `ensureFresh reports fresh after the retried sync completes following a simulated pre-manifest interruption`() = runTest {
        val worktree = GitShadowWorktree(context, "stelekit_freshness_regression_unit_happy", "saf://root")
        File(worktree.worktreeRootPath, "note.md").writeText("- pre-existing content\n")

        val safListing = listOf("note.md" to 5_000L)
        val readSaf: suspend (String) -> String? = { relPath -> if (relPath == "note.md") "- pre-existing content\n" else null }

        // The retry completes: syncShadowAfterInitOrClone (the step interrupted the first time
        // around) now runs to completion via ensureFresh's own isFresh-then-sync structure.
        worktree.ensureFresh(listRecursive = { safListing }, readSafFile = readSaf)

        assertTrue(
            worktree.isFresh(listRecursive = { safListing }),
            "after the retried sync completes, the manifest must reflect the current SAF listing — no leftover staleness from the interrupted first attempt",
        )
    }

    @Test
    fun `a retried clone against a real AndroidGitRepository fixture never leaves ensureFresh reporting a half-synced state as fresh`() = runTest {
        val originDir = createTempDirectory("stelekit_freshness_regression_origin_").toFile()
        Git.init().setDirectory(originDir).call().use { git ->
            setTestIdentity(git)
            File(originDir, "cloned.md").writeText("- from origin\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("seed commit").call()
        }

        val repoRoot = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Afreshness-regression"
        // Seeds BOTH the origin's own file and an extra pre-existing one: syncFromSafRoot's
        // deleteOrphanedShadowFiles step (GitShadowWorktree.kt) removes any shadow-tree file not
        // present in the live SAF listing, so a realistic fixture's SAF root must already reflect
        // what the linked remote contains (the intended production flow: the user's SAF folder IS
        // what's tracked in git) — omitting "cloned.md" here made the first successful sync delete
        // it as a false "orphan", which is a fixture-realism bug, not the retry/freshness behavior
        // this test targets.
        val fakeFs = FakeSafFileSystem().apply {
            seed("$repoRoot/cloned.md", "- from origin\n")
            seed("$repoRoot/pre-existing.md", "- pre-existing local content\n")
        }
        // Fires exactly once, mid-way through the FIRST clone attempt's syncShadowAfterInitOrClone
        // call (i.e. after Git.cloneRepository().call() has already succeeded) — simulating this
        // story's exact named interruption point without touching production code. A raw
        // SocketException (not hand-wrapped in TransportException) is classified Transient by
        // classifyGitFailure directly (it walks the cause chain starting at the exception itself),
        // so AndroidGitRepository.clone()'s own runGitTransportOpWithRetry retries automatically —
        // the same real retry/backoff/beforeRetry-wipe path production code uses, not a re-invoked
        // manual retry.
        val throwingFs = ThrowOnceThenDelegateFileSystem(fakeFs) {
            SocketException("simulated interruption between Git.cloneRepository().call() and syncShadowAfterInitOrClone")
        }

        val repository = newRepository(fileSystem = throwingFs)
        val worktreePath = requireNotNull(repository.shadowWorktreeFor(repoRoot)).worktreeRootPath
        // Ample storage so the pre-clone StatFs guard (Task 8.2.3a, above) never trips.
        ShadowStatFs.registerStats(worktreePath, 2_000_000, 1_000_000, 1_000_000)

        val result = repository.clone(
            url = originDir.absolutePath,
            localPath = repoRoot,
            auth = GitAuth.None,
            onProgress = {},
            onStateChange = {},
        )

        assertTrue(result.isRight(), "the retried clone must eventually succeed once the injected interruption has fired exactly once, got: $result")
        assertTrue(File(worktreePath, "cloned.md").exists(), "git-cloned content must be present after the retry completes")
        assertTrue(
            File(worktreePath, "pre-existing.md").exists(),
            "the pre-existing SAF content must have been folded in by the successful retry's syncShadowAfterInitOrClone",
        )

        val worktree = requireNotNull(repository.shadowWorktreeFor(repoRoot))
        val isFreshAfter = worktree.isFresh(listRecursive = { root -> fakeFs.listFilesRecursiveWithModTimes(root) })
        assertTrue(
            isFreshAfter,
            "ensureFresh()/isFresh() must report the shadow tree as fresh immediately after the retried clone completes — " +
                "no leftover stale/absent manifest state from the interrupted first attempt",
        )
    }

    /**
     * Wraps [delegate], throwing [exceptionToThrow] from the very first
     * [listFilesRecursiveWithModTimes] call — `GitShadowWorktree.syncFromSafRoot`'s exact entry
     * point (via `AndroidGitShadowSupport.syncShadowAfterInitOrClone`) — and delegating normally on
     * every call thereafter. Overrides that method directly rather than the lower-level [listFiles]
     * it's built from: [listFilesRecursiveWithModTimes] is a `FileSystem` interface default method,
     * so a `by delegate`-forwarded call to it dispatches its *internal* `listFiles` calls against
     * `delegate`'s own runtime type, not this wrapper — overriding only `listFiles` here would
     * silently never fire. Models "the interruption fires once, the retry completes normally" for
     * a [FileSystem]-level dependency, the same shape as
     * [dev.stapler.stelekit.git.testsupport.FailureSequence] models it at the `GitRepository` level.
     */
    private class ThrowOnceThenDelegateFileSystem(
        private val delegate: FileSystem,
        private val exceptionToThrow: () -> Throwable,
    ) : FileSystem by delegate {
        private var hasThrown = false

        override suspend fun listFilesRecursiveWithModTimes(path: String): List<Pair<String, Long>> {
            if (!hasThrown) {
                hasThrown = true
                throw exceptionToThrow()
            }
            return delegate.listFilesRecursiveWithModTimes(path)
        }
    }
}
