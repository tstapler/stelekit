// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.platform.NetworkMonitor
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [GitSyncService] covering early-return scenarios (no config / offline / locked
 * vault), [GitSyncService.applyJournalMerge] write failure, and [GitSyncService.abortActiveMerge].
 *
 * All tests use real [EditLock], [GraphLoader], and [GraphWriter] instances with stub
 * dependencies; only [GitRepository] and [GitConfigRepository] are stubbed.
 *
 * Split from a single 751-line `GitSyncServiceTest.kt` — see [GitSyncServiceErrorRoutingTest]
 * (RateLimited/MergeConflict/CredentialExpired routing) and [GitSyncServiceConflictResolutionTest]
 * (`resolveConflicts`/`refreshLocalStatus`) for the other scenario groups. [buildGitSyncTestService]
 * lives in `GitSyncServiceTestFixtures.kt` so the three files don't re-duplicate it;
 * [StubFileSystem]/[StubConfigRepository]/[sampleConfig] live in `dev.stapler.stelekit.git.testsupport`
 * (`commonTest`), shared further with `jvmTest`/`androidUnitTest`.
 *
 * Note on [NetworkMonitor]: it is an `expect class` (not an interface) and cannot be
 * subclassed. Tests that require a specific network state use [assumeTrue] to skip
 * when the precondition cannot be met in the current environment.
 */
class GitSyncServiceTest {

    // ── TC-1: sync() with no git config returns Success ───────────────────────

    /**
     * TC-1: When [GitConfigRepository.getConfig] returns null (no config for this graph),
     * [GitSyncService.sync] must short-circuit immediately and return
     * [Either.Right] wrapping a [SyncState.Success] with no commits made.
     *
     * Precondition: requires network connectivity (network check runs before config load).
     */
    @Test
    fun `sync with no git config returns Right Success with zero commits`() = runTest {
        assumeTrue(
            "Skipped: requires network access for the initial NetworkMonitor.isOnline check",
            NetworkMonitor().isOnline,
        )

        val service = buildGitSyncTestService(
            configRepository = StubConfigRepository(Either.Right(null)),
        )

        val result = service.sync("test-graph")

        assertIs<Either.Right<*>>(result)
        val success = result.value
        assertIs<SyncState.Success>(success)
        assertEquals(0, success.localCommitsMade, "no local commits when no config")
        assertEquals(0, success.remoteCommitsMerged, "no remote commits when no config")
        assertIs<SyncState.Success>(service.syncState.value)
    }

    // ── TC-2: sync() when offline emits SyncState.Error and returns Left(Offline) ──

    /**
     * TC-2: When [NetworkMonitor.isOnline] returns false, [GitSyncService.sync] must
     * emit [SyncState.Error] with [DomainError.GitError.Offline] and return
     * [Either.Left] wrapping the same error.
     *
     * Note: [NetworkMonitor] is an `expect class` (final, not mockable without bytecode tools).
     * This test only runs in environments that are actually offline.
     */
    @Test
    fun `sync when offline emits SyncState Error Offline and returns Left Offline`() = runTest {
        assumeTrue(
            "Skipped: requires offline environment — NetworkMonitor.isOnline was true",
            !NetworkMonitor().isOnline,
        )

        val service = buildGitSyncTestService(
            configRepository = StubConfigRepository(Either.Right(null)),
        )

        val result = service.sync("test-graph")

        assertIs<Either.Left<*>>(result)
        assertEquals(DomainError.GitError.Offline, result.value)
        val state = service.syncState.value
        assertIs<SyncState.Error>(state)
        assertEquals(DomainError.GitError.Offline, state.error)
    }

    // ── TC-3: sync() when vault is locked emits CredentialVaultLocked + Left(AuthFailed) ──

    /**
     * TC-3: When the [CredentialAccess] vault reports locked ([CredentialAccess.isAvailable]
     * returns false), [GitSyncService.sync] must:
     *   - emit [SyncState.CredentialVaultLocked]
     *   - return [Either.Left] wrapping [DomainError.GitError.AuthFailed]
     *
     * Precondition: requires network connectivity so the vault check is reached.
     */
    @Test
    fun `sync when vault is locked emits CredentialVaultLocked and returns Left AuthFailed`() = runTest {
        assumeTrue(
            "Skipped: requires network access for the initial NetworkMonitor.isOnline check",
            NetworkMonitor().isOnline,
        )

        val service = buildGitSyncTestService(
            configRepository = StubConfigRepository(Either.Right(null)),
            credentialAccessProvider = { LockedCredentialAccess },
        )

        val result = service.sync("test-graph")

        assertIs<Either.Left<*>>(result)
        assertIs<DomainError.GitError.AuthFailed>(result.value)
        assertEquals(SyncState.CredentialVaultLocked, service.syncState.value)
    }

    // ── TC-4: applyJournalMerge() when file write fails returns Left(CommitFailed) ──

    /**
     * TC-4: When [FileSystem.writeFile] returns false, [GitSyncService.applyJournalMerge]
     * must return [Either.Left] wrapping [DomainError.GitError.CommitFailed] without
     * calling [GitRepository.commit].
     *
     * [GitRepository] is intentionally a strict stub — if [commit] is called the test
     * will throw [NotImplementedError], surfacing the unexpected call.
     */
    @Test
    fun `applyJournalMerge when write fails returns Left CommitFailed without committing`() = runTest {
        val writeFails = StubFileSystem(writeResult = false)

        val service = buildGitSyncTestService(
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
            fileSystem = writeFails,
        )

        val result = service.applyJournalMerge(
            graphId = "test-graph",
            filePath = "/repo/journals/2026-06-12.md",
            mergedContent = "- merged content",
        )

        assertIs<Either.Left<*>>(result)
        val err = result.value
        assertIs<DomainError.GitError.CommitFailed>(err)
        assertTrue(err.message.isNotBlank(), "CommitFailed must carry a non-blank message")
    }

    // ── TC-5: abortActiveMerge() when no config returns Right(Unit) ───────────

    /**
     * TC-5: When [GitConfigRepository.getConfig] returns null, [GitSyncService.abortActiveMerge]
     * must return [Either.Right] of [Unit] immediately without calling [GitRepository.abortMerge].
     *
     * [GitRepository] is intentionally a strict stub — any call to [abortMerge] will fail
     * the test with [NotImplementedError].
     */
    @Test
    fun `abortActiveMerge with no config is a no-op and returns Right Unit`() = runTest {
        val service = buildGitSyncTestService(
            configRepository = StubConfigRepository(Either.Right(null)),
        )

        val result = service.abortActiveMerge("test-graph")

        assertIs<Either.Right<*>>(result)
        assertEquals(Unit, result.value)
    }

    // ── TC-5b (Story 3.3.3/Task 3.3.3a): abortActiveMerge() happy path ────────

    /**
     * TC-5b (Story 3.3.3): confirms the actual happy path — a resolvable [GitConfig] exists,
     * [GitRepository.abortMerge] is invoked exactly once, and [GitSyncService.syncState] resolves
     * to [SyncState.Idle]. Closes the gap `docs/tasks/git-sync-ux.md` line 7 flagged: the only
     * prior `abortActiveMerge` test (TC-5 above) covered exclusively the no-config edge case.
     */
    @Test
    fun `abortActiveMerge with a resolvable config calls abortMerge exactly once and transitions to Idle`() = runTest {
        var abortMergeCallCount = 0
        val gitRepository = object : StubGitRepository() {
            override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> {
                abortMergeCallCount++
                return Unit.right()
            }
        }

        val service = buildGitSyncTestService(
            gitRepository = gitRepository,
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
        )

        val result = service.abortActiveMerge("test-graph")

        assertIs<Either.Right<*>>(result)
        assertEquals(1, abortMergeCallCount, "abortMerge must be invoked exactly once")
        assertEquals(SyncState.Idle, service.syncState.value)
    }

    // ── TC-5c (Story 3.3.3/Task 3.3.3a): abortActiveMerge() failed abort never falsely reports Idle ──

    /**
     * TC-5c (Story 3.3.3): when [GitRepository.abortMerge] fails, [GitSyncService.syncState] must
     * NOT transition to [SyncState.Idle] — a failed abort must never falsely tell the user the
     * merge state was cleared. To make "does not transition to Idle" a meaningful assertion (a
     * fresh [GitSyncService] already starts at [SyncState.Idle]), this first drives the state to a
     * known non-Idle value ([SyncState.Committing], via [GitSyncService.commitLocalChanges] failing
     * at its `stageSubdir` step) before calling [GitSyncService.abortActiveMerge].
     */
    @Test
    fun `abortActiveMerge when abortMerge fails does not transition syncState to Idle`() = runTest {
        val gitRepository = object : StubGitRepository() {
            override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
                GitStatus(hasLocalChanges = true, untrackedFiles = emptyList(), modifiedFiles = listOf("pages/foo.md")).right()

            override suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit> =
                DomainError.GitError.CommitFailed("stage failed").left()

            override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> =
                DomainError.GitError.CommitFailed("abort failed").left()
        }

        val service = buildGitSyncTestService(
            gitRepository = gitRepository,
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
        )

        // Drive syncState to a known non-Idle value first (Committing), so the assertion below
        // actually proves "no transition happened" rather than "it was already Idle."
        service.commitLocalChanges("test-graph")
        assertEquals(SyncState.Committing, service.syncState.value, "precondition: state must be non-Idle before the abort attempt")

        val result = service.abortActiveMerge("test-graph")

        assertIs<Either.Left<*>>(result)
        assertEquals(
            SyncState.Committing,
            service.syncState.value,
            "a failed abortMerge must never transition syncState to Idle",
        )
    }
}
