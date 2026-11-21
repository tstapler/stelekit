// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.git.merge.MergePolicy
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.ConflictHunk
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 1.5: step-boundary cancellation, the non-cancellable commit, and the extracted merge unit. */
class GitSyncCancelBoundaryTest {
    private val gitDir: File = Files.createTempDirectory("cancel_boundary_").toFile()
    private val config: GitConfig = sampleConfig.copy(repoRoot = RepoRoot(gitDir.absolutePath))
    private val gate = RepoOpGate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val services = mutableListOf<GitSyncService>()

    @AfterTest
    fun tearDown() {
        services.forEach { it.shutdown() }
        scope.coroutineContext[Job]?.cancel()
        gitDir.deleteRecursively()
    }

    private fun key(): String = checkNotNull(RepoOpGate.keyForOrNull("${gitDir.absolutePath}/.git") { File(it).canonicalFile.path })

    private fun service(git: GitRepository, loader: GraphLoader? = null, fs: StubFileSystem = StubFileSystem()): GitSyncService {
        val configRepo = StubConfigRepository(Either.Right(config))
        val built = if (loader != null) {
            buildGitSyncTestService(git, configRepo, fs, isOnline = { true }, gate = gate, graphLoader = loader)
        } else {
            buildGitSyncTestService(git, configRepo, fs, isOnline = { true }, gate = gate)
        }
        return built.also { services += it }
    }

    /** Repository whose calls are recorded and whose steps can block, ignoring cancellation, until released. */
    private open class Repo(var localChanges: Boolean = false, var remoteChanges: Boolean = false) : StubGitRepository() {
        val calls = CopyOnWriteArrayList<String>()
        @Volatile var onStatus: suspend () -> Unit = {}
        @Volatile var onCommit: suspend () -> Unit = {}
        @Volatile var onFetch: suspend () -> Unit = {}
        @Volatile var onMergeDone: () -> Unit = {}
        @Volatile var mergeResult = MergeResult(hasConflicts = false, conflicts = emptyList(), changedFiles = emptyList(), mergedCommitCount = 1)
        @Volatile var commitResult: Either<DomainError.GitError, String> = "sha".right()

        fun count(name: String) = calls.count { it == name }

        override fun gateKeyFor(config: GitConfig): String? =
            RepoOpGate.keyForOrNull("${config.repoRoot.value}/.git") { File(it).canonicalFile.path }
        override suspend fun beginOwnedOperation(config: GitConfig, info: OwnedOperationInfo) = LockVerdict.NoLock.right()
        override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> {
            onStatus()
            return GitStatus(localChanges, emptyList(), if (localChanges) listOf("pages/a.md") else emptyList()).right()
        }
        override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> {
            calls += "commit.start"
            onCommit()
            calls += "commit"
            return commitResult
        }
        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
            calls += "fetch"
            onFetch()
            return FetchResult(remoteChanges, if (remoteChanges) 1 else 0).right()
        }
        override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> {
            calls += "merge"
            return mergeResult.also { onMergeDone() }.right()
        }
        override suspend fun stageSubdir(config: GitConfig, excludePaths: Set<String>) = Unit.right()
        override suspend fun markResolved(config: GitConfig, filePath: String) = Unit.right()
        override suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide) = Unit.right()
        override suspend fun abortMerge(config: GitConfig) = Unit.right().also { calls += "abort" }
        override suspend fun push(config: GitConfig) = Unit.right().also { calls += "push" }
    }

    /** Blocks the calling thread (a non-interruptible JGit-style call) until [latch] opens. */
    private fun blocking(entered: CompletableDeferred<Unit>, latch: CountDownLatch): suspend () -> Unit = {
        entered.complete(Unit)
        latch.await()
    }

    @Test
    fun cancelDuringFetch_should_NotMerge_AndCancelBeforeCommitShouldNotCommit() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val latch = CountDownLatch(1)
        val git = Repo(remoteChanges = true).apply { onFetch = blocking(entered, latch) }
        val svc = service(git)

        val run = scope.async { svc.sync("g") }
        withTimeout(20_000) { entered.await() }
        run.cancel()
        latch.countDown()
        run.join()

        assertEquals(0, git.count("merge"), "a cancel during fetch must not start the merge")
        assertEquals(0, git.count("push"))
        assertEquals(SyncState.Idle, svc.syncState.value)
        assertFalse(gate.isHeld(key()))

        val statusEntered = CompletableDeferred<Unit>()
        val statusLatch = CountDownLatch(1)
        val git2 = Repo(localChanges = true).apply { onStatus = blocking(statusEntered, statusLatch) }
        val svc2 = service(git2)
        val run2 = scope.async { svc2.sync("g") }
        withTimeout(20_000) { statusEntered.await() }
        run2.cancel()
        statusLatch.countDown()
        run2.join()

        assertEquals(0, git2.count("commit.start"), "a cancel before the commit step must not commit")
        assertFalse(gate.isHeld(key()))
    }

    @Test
    fun cancelThenImmediateRepress_should_NotOverlap_When_BlockingCallStillRunning() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val latch = CountDownLatch(1)
        val git = Repo(localChanges = true).apply { onCommit = blocking(entered, latch) }
        val svc = service(git)

        val first = scope.async { svc.sync("g") }
        withTimeout(20_000) { entered.await() }
        first.cancel()
        // Scheduled origin: a UI press would queue a follow-up rerun that re-acquires the gate after the first run ends.
        val second = withTimeout(20_000) { svc.sync("g", origin = SyncOrigin.Scheduled) }

        assertIs<DomainError.GitError.SyncInProgress>(second.leftOrNull())
        assertTrue(gate.isHeld(key()), "the gate stays held while the blocking call runs")
        assertTrue(svc.syncState.value !is SyncState.Idle, "state must not read Idle while a call still runs: ${svc.syncState.value}")
        assertEquals(1, git.count("commit.start"))

        latch.countDown()
        first.join()

        assertFalse(gate.isHeld(key()))
        assertEquals(0, git.count("fetch"), "the pipeline stops before fetch after the cancel")
    }

    @Test
    fun commit_should_CompleteAndPipelineStopBeforeFetch_When_CancelRequestedDuringCommit() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val git = Repo(localChanges = true).apply { onCommit = { entered.complete(Unit); proceed.await() } }
        val svc = service(git)

        val run = scope.async { svc.sync("g") }
        withTimeout(20_000) { entered.await() }
        run.cancel()
        proceed.complete(Unit)
        run.join()

        assertEquals(1, git.count("commit"), "the commit must finish once started")
        assertEquals(0, git.count("fetch"))
        assertEquals(SyncState.Idle, svc.syncState.value)
        assertFalse(gate.isHeld(key()))
    }

    private val realConflict = ConflictFile(
        filePath = "/repo/real.md",
        wikiRelativePath = "real.md",
        hunks = listOf(ConflictHunk(id = "h", localLines = listOf("- a"), remoteLines = listOf("- b"))),
        rawContent = "<<<<<<< LOCAL\n- a\n=======\n- b\n>>>>>>> REMOTE\n",
    )

    @Test
    fun mergeUnit_should_NotFallThroughToPush_When_ConflictOrCommitFailureExitsInsideNonCancellable() = runBlocking<Unit> {
        val conflicted = Repo(remoteChanges = true).apply {
            mergeResult = MergeResult(true, listOf(realConflict), emptyList(), 1)
        }
        val r1 = service(conflicted).sync("g")
        assertIs<DomainError.GitError.MergeConflict>(r1.leftOrNull())
        assertEquals(0, conflicted.count("push"), "a conflict in the unit must never reach push")

        val clean = ConflictFile("/repo/clean.md", "clean.md", emptyList(), rawContent = "- merged\n", autoResolvedBy = MergePolicy.LogseqUnion)
        val failing = Repo(remoteChanges = true).apply {
            mergeResult = MergeResult(true, listOf(clean), emptyList(), 1)
            commitResult = Either.Left(DomainError.GitError.CommitFailed("boom"))
        }
        val r2 = service(failing).sync("g")
        assertIs<DomainError.GitError.CommitFailed>(r2.leftOrNull())
        assertEquals(0, failing.count("push"), "a commit failure in the unit must never reach push")
        assertEquals(1, failing.count("abort"))
    }

    @Test
    fun mergeUnit_should_ReloadEveryChangedPathBeforeStopping_When_CancelArrivesRightAfterMerge() = runBlocking<Unit> {
        val reloaded = CopyOnWriteArrayList<String>()
        val fs = object : StubFileSystem() {
            override fun invalidateShadow(path: String) { reloaded += path }
        }
        val loader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository())
        val jobRef = AtomicReference<Job>()
        val git = Repo(remoteChanges = true).apply {
            mergeResult = MergeResult(false, emptyList(), listOf("/repo/pages/a.md", "/repo/pages/b.md"), 1)
            onMergeDone = { jobRef.get().cancel() }
        }
        val svc = service(git, loader, fs)

        val run = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { svc.sync("g") }
        jobRef.set(run)
        run.start()
        run.join()

        assertEquals(listOf("/repo/pages/a.md", "/repo/pages/b.md"), reloaded.toList(), "#676: the reload must finish after a cancel")
        assertEquals(0, git.count("push"), "the pipeline stops after the unit")
        assertEquals(SyncState.Idle, svc.syncState.value)
        assertFalse(gate.isHeld(key()))
    }

    // Review round 2 (M3): the #676 commit-then-reload guarantee holds for every merge-completing entry point.
    private fun reloadingFixture(): Pair<CopyOnWriteArrayList<String>, Pair<GraphLoader, StubFileSystem>> {
        val reloaded = CopyOnWriteArrayList<String>()
        val fs = object : StubFileSystem() {
            override fun invalidateShadow(path: String) { reloaded += path }
        }
        return reloaded to (GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository()) to fs)
    }

    @Test
    fun resolveConflicts_should_ReloadResolvedFiles_When_CancelArrivesRightAfterCommit() = runBlocking<Unit> {
        val (reloaded, loaderAndFs) = reloadingFixture()
        val jobRef = AtomicReference<Job>()
        val git = Repo().apply { onCommit = { jobRef.get().cancel() } }
        val svc = service(git, loaderAndFs.first, loaderAndFs.second)

        val run = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            svc.resolveConflicts("g", listOf(realConflict), sideResolutions = mapOf("/repo/real.md" to MergeSide.LOCAL))
        }
        jobRef.set(run)
        run.start()
        run.join()

        assertEquals(1, git.count("commit"))
        assertEquals(listOf("/repo/real.md"), reloaded.toList(), "the reload must finish after a cancel")
        assertFalse(svc.reloadIncomplete.value)
        assertFalse(gate.isHeld(key()))
    }

    @Test
    fun applyJournalMerge_should_ReloadTheFile_When_CancelArrivesRightAfterCommit() = runBlocking<Unit> {
        val (reloaded, loaderAndFs) = reloadingFixture()
        val jobRef = AtomicReference<Job>()
        val git = Repo().apply { onCommit = { jobRef.get().cancel() } }
        val svc = service(git, loaderAndFs.first, loaderAndFs.second)

        val run = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { svc.applyJournalMerge("g", "/repo/journals/j.md", "- merged\n") }
        jobRef.set(run)
        run.start()
        run.join()

        assertEquals(listOf("/repo/journals/j.md"), reloaded.toList(), "the reload must finish after a cancel")
        assertFalse(gate.isHeld(key()))
    }

    @Test
    fun commitLocalChanges_should_FinishTheCommit_When_CancelRequestedDuringIt() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val git = Repo(localChanges = true).apply { onCommit = { entered.complete(Unit); proceed.await() } }
        val svc = service(git)

        val run = scope.async { svc.commitLocalChanges("g") }
        withTimeout(20_000) { entered.await() }
        run.cancel()
        proceed.complete(Unit)
        run.join()

        assertEquals(1, git.count("commit"), "a started commit must finish")
        assertFalse(gate.isHeld(key()))
    }

    @Test
    fun reloadMergedFiles_should_FlagReloadIncomplete_When_CancelledMidReload() = runBlocking<Unit> {
        val fs = object : StubFileSystem() {
            override fun invalidateShadow(path: String) { throw CancellationException("cancelled mid reload") }
        }
        val loader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository())
        val git = Repo(remoteChanges = true).apply { mergeResult = MergeResult(false, emptyList(), listOf("/repo/pages/a.md"), 1) }
        val svc = service(git, loader, fs)

        scope.async { svc.sync("g") }.join()

        assertTrue(svc.reloadIncomplete.value, "a reload stopped by cancellation must be flagged for the next open")
    }

    // PR #683 round 2 (C1): only the caller's wait is bounded; the lease and record stay until the unit really ends.
    @Test
    fun resolveConflicts_should_KeepTheLease_UntilALateCommitFinishes_ThenReleaseAndClearTheFlag() = runBlocking<Unit> {
        val late = CompletableDeferred<Unit>()
        val git = Repo().apply { onCommit = { late.await() } }
        val svc = service(git).also { it.commitUnitTimeoutMs = 300 }

        val result = withTimeout(20_000) {
            svc.resolveConflicts("g", listOf(realConflict), sideResolutions = mapOf("/repo/real.md" to MergeSide.LOCAL))
        }

        assertIs<DomainError.GitError.CommitFailed>(result.leftOrNull())
        assertTrue(result.leftOrNull()!!.message.contains("still running"))
        assertTrue(gate.isHeld(key()), "the abandoned commit still owns the repository")
        assertTrue(svc.reloadIncomplete.value)
        val second = svc.sync("g", origin = SyncOrigin.Scheduled)
        assertIs<DomainError.GitError.SyncInProgress>(second.leftOrNull(), "no second commit may start under the first")
        assertEquals(1, git.count("commit.start"))

        late.complete(Unit)
        withTimeout(20_000) { while (gate.isHeld(key()) || svc.reloadIncomplete.value) kotlinx.coroutines.delay(20) }
        assertFalse(gate.isHeld(key()))
        assertFalse(svc.reloadIncomplete.value, "the sentinel clears when the abandoned unit ends")
    }

    @Test
    fun sync_should_KeepTheLeaseAndNotFlagReload_When_TheMainCommitOutlivesTheWait() = runBlocking<Unit> {
        val late = CompletableDeferred<Unit>()
        val git = Repo(localChanges = true).apply { onCommit = { late.await() } }
        val svc = service(git).also { it.commitUnitTimeoutMs = 300 }

        val result = withTimeout(20_000) { svc.sync("g") }

        assertIs<DomainError.GitError.CommitFailed>(result.leftOrNull())
        assertTrue(gate.isHeld(key()))
        assertFalse(svc.reloadIncomplete.value, "a plain commit reloads nothing, so it must not flag the DB")
        assertIs<DomainError.GitError.SyncInProgress>(svc.sync("g", origin = SyncOrigin.Scheduled).leftOrNull())

        late.complete(Unit)
        withTimeout(20_000) { while (gate.isHeld(key())) kotlinx.coroutines.delay(20) }
    }

    // PR #683 review m1: a later complete reload of other files must not clear an earlier incomplete one.
    @Test
    fun reloadIncomplete_should_StaySticky_UntilTheFailedPathsReloadCompletely() = runBlocking<Unit> {
        val failing = java.util.concurrent.atomic.AtomicBoolean(true)
        val fs = object : StubFileSystem() {
            override fun invalidateShadow(path: String) { if (failing.get() && path == "/repo/a.md") throw CancellationException("reload interrupted") }
        }
        val loader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository())
        val svc = service(Repo(), loader, fs)

        scope.async { svc.applyJournalMerge("g", "/repo/a.md", "- a\n") }.join()
        assertTrue(svc.reloadIncomplete.value, "the failed reload is flagged")
        scope.async { svc.applyJournalMerge("g", "/repo/b.md", "- b\n") }.join()
        assertTrue(svc.reloadIncomplete.value, "reloading another file must not hide it")
        failing.set(false)
        scope.async { svc.applyJournalMerge("g", "/repo/a.md", "- a\n") }.join()
        assertFalse(svc.reloadIncomplete.value, "cleared once the failed path reloads completely")
    }

    // PR #683 round 3: the lease handoff is per run, and record/heartbeat/busy count end exactly once.
    private class CountingRepo(private val inner: Repo) : GitRepository by inner {
        val begins = java.util.concurrent.atomic.AtomicInteger()
        val ends = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        override suspend fun beginOwnedOperation(config: GitConfig, info: OwnedOperationInfo): Either<DomainError.GitError, LockVerdict> {
            begins.incrementAndGet()
            return LockVerdict.NoLock.right()
        }
        override suspend fun endOwnedOperation(config: GitConfig) {
            ends.computeIfAbsent(config.repoRoot.value) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
        }
        override fun gateKeyFor(config: GitConfig): String? = inner.gateKeyFor(config)
        override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> =
            if (config.repoRoot.value == hangingRoot) inner.commit(config, message) else "sha".right()
        lateinit var hangingRoot: String
    }

    private class TwoGraphConfigs(private val byGraph: Map<String, GitConfig>) : GitConfigRepository {
        override suspend fun getConfig(graphId: String): Either<DomainError, GitConfig?> = Either.Right(byGraph[graphId])
        override suspend fun saveConfig(config: GitConfig): Either<DomainError, Unit> = Unit.right()
        override suspend fun deleteConfig(graphId: String): Either<DomainError, Unit> = Unit.right()
        override fun observeConfig(graphId: String) = kotlinx.coroutines.flow.flowOf<Either<DomainError, GitConfig?>>(Either.Right(byGraph[graphId]))
    }

    @Test
    fun abandonedUnit_should_HoldOnlyItsOwnRunsLease_AndEndEverythingExactlyOnce() = runBlocking<Unit> {
        val dirB = Files.createTempDirectory("cancel_boundary_b_").toFile()
        try {
            val configB = sampleConfig.copy(repoRoot = RepoRoot(dirB.absolutePath))
            val late = CompletableDeferred<Unit>()
            val inner = Repo(localChanges = true).apply { onCommit = { late.await() } }
            val repo = CountingRepo(inner)
            repo.hangingRoot = config.repoRoot.value
            val svc = buildGitSyncTestService(
                repo, TwoGraphConfigs(mapOf("a" to config, "b" to configB)), StubFileSystem(),
                isOnline = { true }, gate = gate,
            ).also { services += it; it.commitUnitTimeoutMs = 300 }

            val a = withTimeout(20_000) { svc.sync("a") }
            assertIs<DomainError.GitError.CommitFailed>(a.leftOrNull())
            val keyB = checkNotNull(RepoOpGate.keyForOrNull("${dirB.absolutePath}/.git") { File(it).canonicalFile.path })
            assertTrue(gate.isHeld(key()), "A's abandoned commit keeps A's lease")
            assertFalse(gate.isHeld(keyB), "B is a different repository and must not be held by A's unit")
            assertEquals(0, repo.ends[config.repoRoot.value]?.get() ?: 0, "A's record must not end while its unit runs")

            val b = withTimeout(20_000) { svc.sync("b") }
            assertTrue(b.isRight(), "B runs independently while A's unit lingers, got $b")
            assertEquals(1, repo.ends[configB.repoRoot.value]?.get(), "B's record ends exactly once, on its own")
            assertFalse(gate.isHeld(keyB))
            assertTrue(gate.isHeld(key()), "A's lease is still held after B finished")
            assertEquals(0, repo.ends[config.repoRoot.value]?.get() ?: 0)

            late.complete(Unit)
            withTimeout(20_000) { while (gate.isHeld(key())) kotlinx.coroutines.delay(20) }
            kotlinx.coroutines.delay(100)
            assertEquals(1, repo.ends[config.repoRoot.value]?.get(), "A's owner record ends exactly once")
            assertEquals(2, repo.begins.get(), "one claim per run")
        } finally {
            dirB.deleteRecursively()
        }
    }
}
