// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository

/**
 * Shared [GitSyncService] test fixtures for `GitSyncServiceTest`, `GitSyncServiceErrorRoutingTest`,
 * and `GitSyncServiceConflictResolutionTest` — these three were originally one 751-line
 * `GitSyncServiceTest.kt` (kotlin-file-size finding); kept in one file here so the split doesn't
 * re-duplicate the same stub/config/builder boilerplate three times over.
 *
 * [StubFileSystem]/[StubConfigRepository]/[sampleConfig] live in `dev.stapler.stelekit.git.testsupport`
 * (`commonTest`), shared with `jvmTest`/`androidUnitTest` — see [StubGitRepository]'s KDoc for why.
 */

/** [CredentialAccess] stub that reports the vault as locked. */
internal object LockedCredentialAccess : CredentialAccess {
    override fun retrieve(key: String): String? = null
    override fun store(key: String, value: String) {}
    override fun delete(key: String) {}
    override fun isAvailable(): Boolean = false
}

/** Builds a [GitSyncService] wired with the provided stubs and safe no-op defaults. */
internal fun buildGitSyncTestService(
    gitRepository: GitRepository = StubGitRepository(),
    configRepository: GitConfigRepository,
    fileSystem: FileSystem = StubFileSystem(),
    networkMonitor: NetworkMonitor = NetworkMonitor(),
    credentialAccessProvider: (() -> CredentialAccess)? = null,
    isOnline: (() -> Boolean)? = null,
    gate: RepoOpGate = RepoOpGate(),
    pausedRetryPolicy: PausedRetryPolicy = PausedRetryPolicy(),
    graphLoader: GraphLoader = GraphLoader(
        fileSystem = StubFileSystem(),
        pageRepository = InMemoryPageRepository(),
        blockRepository = InMemoryBlockRepository(),
    ),
): GitSyncService {
    val graphWriter = GraphWriter(fileSystem = fileSystem)
    return GitSyncService(
        gitRepository = gitRepository,
        graphLoader = graphLoader,
        graphWriter = graphWriter,
        editLock = EditLock(),
        configRepository = configRepository,
        gate = gate,
        networkMonitor = networkMonitor,
        fileSystem = fileSystem,
        credentialAccessProvider = credentialAccessProvider,
        isOnline = isOnline ?: { networkMonitor.isOnline },
        pausedRetryPolicy = pausedRetryPolicy,
    )
}

/** Repository operations a [GateAwareFakeGitRepository] can be told to hold open. */
internal enum class FakeGitOp { Commit, Fetch, Merge }

/**
 * Records overlapping repository calls and, while a blocked op is held, keeps a real `index.lock`
 * under [gitDir]. [removeStaleLockFile] mirrors today's age-only deletion (60 s) so the single-flight
 * regression tests reproduce the defect; Task 1.4/1.2 make that deletion unreachable for a live run.
 */
internal class GateAwareFakeGitRepository(
    private val gitDir: java.io.File,
    var hasRemoteChanges: Boolean = false,
    var mergeInProgress: Boolean = false,
) : StubGitRepository() {
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
    private val maxConcurrentCalls = java.util.concurrent.atomic.AtomicInteger(0)
    private val staleLockChecks = java.util.concurrent.atomic.AtomicInteger(0)
    private val blockedOp = java.util.concurrent.atomic.AtomicReference<FakeGitOp?>(null)
    private val opEntered = java.util.concurrent.atomic.AtomicReference(kotlinx.coroutines.CompletableDeferred<Unit>())
    private val release = java.util.concurrent.atomic.AtomicReference(kotlinx.coroutines.CompletableDeferred<Unit>())
    val indexLock: java.io.File = java.io.File(gitDir, "index.lock")

    val maxConcurrent: Int get() = maxConcurrentCalls.get()
    val removeStaleLockCalls: Int get() = staleLockChecks.get()

    /** Next call of [op] suspends (holding `index.lock`) until [releaseBlocked]. */
    fun blockAt(op: FakeGitOp) {
        opEntered.set(kotlinx.coroutines.CompletableDeferred())
        release.set(kotlinx.coroutines.CompletableDeferred())
        blockedOp.set(op)
    }

    suspend fun awaitBlocked() = opEntered.get().await()

    fun releaseBlocked() {
        release.get().complete(Unit)
    }

    private suspend fun <T> call(op: FakeGitOp?, body: () -> T): T {
        val n = inFlight.incrementAndGet()
        maxConcurrentCalls.updateAndGet { maxOf(it, n) }
        try {
            if (op != null && blockedOp.compareAndSet(op, null)) {
                indexLock.writeText("held")
                opEntered.get().complete(Unit)
                try { release.get().await() } finally { indexLock.delete() }
            }
            return body()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override suspend fun status(config: GitConfig) = call(null) {
        GitStatus(hasLocalChanges = true, untrackedFiles = emptyList(), modifiedFiles = listOf("pages/a.md")).right()
    }
    override suspend fun stageSubdir(config: GitConfig, excludePaths: Set<String>) = call(null) { Unit.right() }
    override suspend fun commit(config: GitConfig, message: String) = call(FakeGitOp.Commit) { "sha".right() }
    override suspend fun fetch(config: GitConfig) = call(FakeGitOp.Fetch) {
        FetchResult(hasRemoteChanges = hasRemoteChanges, remoteCommitCount = if (hasRemoteChanges) 1 else 0).right()
    }
    override suspend fun merge(config: GitConfig) = call(FakeGitOp.Merge) {
        mergeInProgress = false
        MergeResult(hasConflicts = false, conflicts = emptyList(), changedFiles = emptyList()).right()
    }
    override suspend fun push(config: GitConfig) = call(null) { Unit.right() }
    override suspend fun repositoryHealth(config: GitConfig): RepositoryHealth =
        if (mergeInProgress) RepositoryHealth(safe = false, state = "MERGING") else RepositoryHealth.Clean

    override suspend fun removeStaleLockFile(config: GitConfig) = call(null) {
        staleLockChecks.incrementAndGet()
        if (indexLock.exists() && System.currentTimeMillis() - indexLock.lastModified() > STALE_LOCK_AGE_MS) indexLock.delete()
        Unit.right()
    }

    private companion object {
        const val STALE_LOCK_AGE_MS = 60_000L
    }
}
