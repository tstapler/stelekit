// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [GitSyncService] covering early-return scenarios that do not reach
 * graphLoader or graphWriter calls.
 *
 * All tests use real [EditLock], [GraphLoader], and [GraphWriter] instances with stub
 * dependencies; only [GitRepository] and [GitConfigRepository] are stubbed.
 *
 * Note on [NetworkMonitor]: it is an `expect class` (not an interface) and cannot be
 * subclassed. Tests that require a specific network state use [assumeTrue] to skip
 * when the precondition cannot be met in the current environment.
 */
class GitSyncServiceTest {

    // ── Stub helpers ──────────────────────────────────────────────────────────

    /** Minimal [FileSystem] stub — safe defaults for all methods. */
    private open class StubFileSystem(
        private val writeResult: Boolean = true,
    ) : FileSystem {
        override fun getDefaultGraphPath() = "/tmp"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String): Boolean = writeResult
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = true
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
        override fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {}
        override fun stopExternalChangeDetection() {}
    }

    /**
     * [GitRepository] stub that throws [NotImplementedError] for any method not overridden.
     * Each test overrides only the methods its scenario exercises.
     */
    private open class StubGitRepository : GitRepository {
        override suspend fun isGitRepo(path: String): Boolean = error("not implemented in stub")
        override suspend fun init(repoRoot: String): Either<DomainError.GitError, Unit> = error("not implemented in stub")
        override suspend fun clone(url: String, localPath: String, auth: GitAuth, onProgress: (String) -> Unit): Either<DomainError.GitError, Unit> = error("not implemented in stub")
        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> = error("not implemented in stub")
        override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> = error("not implemented in stub")
        override suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit> = error("not implemented in stub")
        override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> = error("not implemented in stub")
        override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> = error("not implemented in stub")
        override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> = error("not implemented in stub")
        override suspend fun log(config: GitConfig, maxCount: Int): Either<DomainError.GitError, List<GitCommit>> = error("not implemented in stub")
        override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> = error("not implemented in stub")
        override suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide): Either<DomainError.GitError, Unit> = error("not implemented in stub")
        override suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit> = error("not implemented in stub")
        override suspend fun hasDetachedHead(config: GitConfig): Boolean = false
        override suspend fun removeStaleLockFile(config: GitConfig): Either<DomainError.GitError, Unit> = Unit.right()
    }

    /** [GitConfigRepository] that returns a fixed result for [getConfig]. */
    private class StubConfigRepository(
        private val configResult: Either<DomainError, GitConfig?>,
    ) : GitConfigRepository {
        override suspend fun getConfig(graphId: String): Either<DomainError, GitConfig?> = configResult
        override suspend fun saveConfig(config: GitConfig): Either<DomainError, Unit> = Unit.right()
        override suspend fun deleteConfig(graphId: String): Either<DomainError, Unit> = Unit.right()
        override fun observeConfig(graphId: String): Flow<Either<DomainError, GitConfig?>> =
            flowOf(configResult)
    }

    /** [CredentialAccess] stub that reports the vault as locked. */
    private object LockedCredentialAccess : CredentialAccess {
        override fun retrieve(key: String): String? = null
        override fun store(key: String, value: String) {}
        override fun delete(key: String) {}
        override fun isAvailable(): Boolean = false
    }

    /** Minimal [GitConfig] for tests that need a valid config. */
    private val sampleConfig = GitConfig(
        graphId = "test-graph",
        repoRoot = "/repo",
        wikiSubdir = "",
        authType = GitAuthType.NONE,
    )

    /** Builds a [GitSyncService] wired with the provided stubs and safe no-op defaults. */
    private fun buildService(
        gitRepository: GitRepository = StubGitRepository(),
        configRepository: GitConfigRepository,
        fileSystem: FileSystem = StubFileSystem(),
        networkMonitor: NetworkMonitor = NetworkMonitor(),
        credentialAccessProvider: (() -> CredentialAccess)? = null,
    ): GitSyncService {
        val stubFs = StubFileSystem()
        val graphLoader = GraphLoader(
            fileSystem = stubFs,
            pageRepository = InMemoryPageRepository(),
            blockRepository = InMemoryBlockRepository(),
        )
        val graphWriter = GraphWriter(fileSystem = fileSystem)
        return GitSyncService(
            gitRepository = gitRepository,
            graphLoader = graphLoader,
            graphWriter = graphWriter,
            editLock = EditLock(),
            configRepository = configRepository,
            networkMonitor = networkMonitor,
            fileSystem = fileSystem,
            credentialAccessProvider = credentialAccessProvider,
        )
    }

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

        val service = buildService(
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

        val service = buildService(
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

        val service = buildService(
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

        val service = buildService(
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
        val service = buildService(
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

        val service = buildService(
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

        val service = buildService(
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

    // ── TC-6: sync() when commit returns RateLimited emits SyncState.RateLimited ──

    /**
     * TC-6 (Story 1.3.3): When [GitRepository.commit] returns
     * [DomainError.GitError.RateLimited], [GitSyncService.sync] must emit
     * [SyncState.RateLimited] carrying the same `retryAfterSeconds`, not the generic
     * [SyncState.Error] the pre-Epic-1.3 fallback would have produced.
     *
     * Precondition: requires network connectivity so sync reaches the commit step (step 5).
     */
    @Test
    fun `sync when commit returns RateLimited emits SyncState RateLimited not Error`() = runTest {
        assumeTrue(
            "Skipped: requires network access for the initial NetworkMonitor.isOnline check",
            NetworkMonitor().isOnline,
        )

        val rateLimitedGitRepository = object : StubGitRepository() {
            override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
                GitStatus(hasLocalChanges = true, untrackedFiles = emptyList(), modifiedFiles = listOf("pages/foo.md")).right()

            override suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit> = Unit.right()

            override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> =
                DomainError.GitError.RateLimited(retryAfterSeconds = 30).left()
        }

        val service = buildService(
            gitRepository = rateLimitedGitRepository,
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
        )

        val result = service.sync("test-graph")

        assertIs<Either.Left<*>>(result)
        assertIs<DomainError.GitError.RateLimited>(result.value)
        val state = service.syncState.value
        assertIs<SyncState.RateLimited>(state)
        assertEquals(30, state.retryAfterSeconds)
    }

    // ── TC-7: fetchOnly() when fetch returns RateLimited emits SyncState.RateLimited ──

    /**
     * TC-7 (Story 1.3.3): When [GitRepository.fetch] returns
     * [DomainError.GitError.RateLimited] during [GitSyncService.fetchOnly], the same
     * `RateLimited` branch must fire at this independent call site — proving all 5
     * `.onLeft` sites named in Task 1.3.3b got the branch, not just the `sync()` ones.
     *
     * Precondition: requires network connectivity so fetchOnly reaches the fetch call.
     */
    @Test
    fun `sync when fetch returns RateLimited during fetchOnly emits SyncState RateLimited`() = runTest {
        assumeTrue(
            "Skipped: requires network access for the initial NetworkMonitor.isOnline check",
            NetworkMonitor().isOnline,
        )

        val rateLimitedGitRepository = object : StubGitRepository() {
            override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> =
                DomainError.GitError.RateLimited(retryAfterSeconds = 15).left()
        }

        val service = buildService(
            gitRepository = rateLimitedGitRepository,
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
        )

        val result = service.fetchOnly("test-graph")

        assertIs<Either.Left<*>>(result)
        assertIs<DomainError.GitError.RateLimited>(result.value)
        val state = service.syncState.value
        assertIs<SyncState.RateLimited>(state)
        assertEquals(15, state.retryAfterSeconds)
    }

    // ── TC-8: sync() when push returns MergeConflict emits ConflictPending, not generic Error ──

    /**
     * TC-8 (Story 3.2.3 / Task 3.2.3b): When [GitRepository.push] returns
     * [DomainError.GitError.MergeConflict] — GitHub's ref-PATCH `409`/`422` and GitLab's
     * commits-POST `400` are both mapped to this same error type before reaching
     * [GitSyncService] — [GitSyncService.sync] must emit [SyncState.ConflictPending] carrying a
     * [ConflictFile] per conflicting path, not the generic [SyncState.Error] the pre-Epic-3.2
     * push `.onLeft` would have produced (it only special-cased `RateLimited`).
     *
     * Precondition: requires network connectivity so sync reaches the push step (step 9).
     */
    @Test
    fun `sync when push returns MergeConflict emits ConflictPending not generic Error, for both GitHub and GitLab shapes`() = runTest {
        assumeTrue(
            "Skipped: requires network access for the initial NetworkMonitor.isOnline check",
            NetworkMonitor().isOnline,
        )

        // Represents both hosts' push-time conflict signal after DomainError mapping: GitHub's
        // 409/422 ref-PATCH race (single unknown path) and GitLab's 400 commits-POST race
        // (named path(s) extracted from the response body) both arrive here as the same
        // MergeConflict shape — this test exercises the shared GitSyncService routing logic,
        // not host-specific response parsing (that's WasmGitWriteServiceAlgorithmsTest's job).
        for (conflictPaths in listOf(listOf("pages/Foo.md"), listOf("<unknown — remote advanced during push>"))) {
            val conflictingGitRepository = object : StubGitRepository() {
                override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
                    GitStatus(hasLocalChanges = false, untrackedFiles = emptyList(), modifiedFiles = emptyList()).right()

                override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> =
                    FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()

                override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> =
                    DomainError.GitError.MergeConflict(
                        conflictCount = conflictPaths.size,
                        conflictPaths = conflictPaths,
                    ).left()
            }

            val service = buildService(
                gitRepository = conflictingGitRepository,
                configRepository = StubConfigRepository(Either.Right(sampleConfig)),
            )

            val result = service.sync("test-graph")

            assertIs<Either.Left<*>>(result)
            assertIs<DomainError.GitError.MergeConflict>(result.value)
            val state = service.syncState.value
            assertIs<SyncState.ConflictPending>(state)
            assertEquals(
                conflictPaths.map { ConflictFile(it, it, emptyList()) },
                state.conflicts,
                "push-time MergeConflict must route to ConflictPending with a ConflictFile per path",
            )
        }
    }

    // ── TC-9 (Story 3.4.3): a missing/expired token (CredentialExpired) routes to
    // SyncState.CredentialExpired, not a generic Error, at both the sync() commit step and the
    // fetchOnly() fetch step ──────────────────────────────────────────────────────────────────

    /**
     * TC-9 (Story 3.4.3 / Task 3.4.3b): when [GitRepository.commit] returns
     * [DomainError.GitError.CredentialExpired] — the shape `WasmGitWriteService.mapHttpFailure`
     * produces for a `401`, or a `403` without `Retry-After`, i.e. a missing/rejected token —
     * [GitSyncService.sync] must emit [SyncState.CredentialExpired], not the generic
     * [SyncState.Error] the pre-Story-3.4.3 fallback would have produced. Web's session-scoped PAT
     * auth (`GitAuthType.HTTPS_TOKEN`) never matches the existing `AuthFailed`+`GITHUB_OAUTH`
     * special case (`GitSyncService.kt`'s fetch-step branch), which is exactly the gap this story
     * closes by mapping straight to `CredentialExpired` instead of routing through `AuthFailed`.
     */
    @Test
    fun `sync when commit returns CredentialExpired emits SyncState CredentialExpired not Error`() = runTest {
        assumeTrue(
            "Skipped: requires network access for the initial NetworkMonitor.isOnline check",
            NetworkMonitor().isOnline,
        )

        val credentialExpiredGitRepository = object : StubGitRepository() {
            override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
                GitStatus(hasLocalChanges = true, untrackedFiles = emptyList(), modifiedFiles = listOf("pages/foo.md")).right()

            override suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit> = Unit.right()

            override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> =
                DomainError.GitError.CredentialExpired("Your git host rejected the configured token").left()
        }

        val service = buildService(
            gitRepository = credentialExpiredGitRepository,
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
        )

        val result = service.sync("test-graph")

        assertIs<Either.Left<*>>(result)
        assertIs<DomainError.GitError.CredentialExpired>(result.value)
        assertEquals(SyncState.CredentialExpired("test-graph"), service.syncState.value)
    }

    /**
     * TC-9 (Story 3.4.3): the same [DomainError.GitError.CredentialExpired] routing at the
     * independent `fetchOnly()` fetch-step call site — proving all 5 `.onLeft` sites named in
     * Task 3.4.3b got the branch, not just `sync()`'s commit step.
     */
    @Test
    fun `fetchOnly when fetch returns CredentialExpired emits SyncState CredentialExpired not Error`() = runTest {
        assumeTrue(
            "Skipped: requires network access for the initial NetworkMonitor.isOnline check",
            NetworkMonitor().isOnline,
        )

        val credentialExpiredGitRepository = object : StubGitRepository() {
            override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> =
                DomainError.GitError.CredentialExpired("Your git host rejected the configured token").left()
        }

        val service = buildService(
            gitRepository = credentialExpiredGitRepository,
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
        )

        val result = service.fetchOnly("test-graph")

        assertIs<Either.Left<*>>(result)
        assertIs<DomainError.GitError.CredentialExpired>(result.value)
        assertEquals(SyncState.CredentialExpired("test-graph"), service.syncState.value)
    }
}
