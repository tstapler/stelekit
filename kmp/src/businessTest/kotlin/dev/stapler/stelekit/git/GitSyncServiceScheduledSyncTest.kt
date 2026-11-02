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
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [GitSyncService.runScheduledSync]: policy plus sync or fetch, against a call-recording fake repository. */
class GitSyncServiceScheduledSyncTest {

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private class Spy : StubGitRepository() {
        var health = RepositoryHealth.Clean
        var dirty = false
        var remoteChanges = false
        var fetchResult: Either<DomainError.GitError, FetchResult>? = null
        var pushResult: Either<DomainError.GitError, Unit> = Unit.right()
        var mergeConflicts: List<ConflictFile> = emptyList()
        var deletions = DeletionSummary.None
        val calls = mutableListOf<String>()

        override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
            GitStatus(dirty, emptyList(), if (dirty) listOf("journals/a.md") else emptyList()).right()

        override suspend fun repositoryHealth(config: GitConfig): RepositoryHealth {
            calls += "health"
            return health
        }

        override suspend fun deletionSummary(config: GitConfig, scope: GuardScope) = deletions

        override suspend fun stageSubdir(config: GitConfig, excludePaths: Set<String>): Either<DomainError.GitError, Unit> {
            calls += "stage"
            return Unit.right()
        }

        override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> {
            calls += "commit"
            return "sha".right()
        }

        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
            calls += "fetch"
            return fetchResult ?: FetchResult(remoteChanges, if (remoteChanges) 2 else 0).right()
        }

        override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> {
            calls += "merge"
            return MergeResult(mergeConflicts.isNotEmpty(), mergeConflicts, listOf("journals/a.md"), mergedCommitCount = 2).right()
        }

        override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> {
            calls += "push"
            return pushResult
        }

        override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> {
            calls += "abort"
            return Unit.right()
        }

        override suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide) = Unit.right()
        override suspend fun markResolved(config: GitConfig, filePath: String) = Unit.right()

        fun mutatingCalls() = calls.filter { it in setOf("stage", "commit", "merge", "push", "abort") }
    }

    private class Harness(
        val spy: Spy = Spy(),
        val settings: MapSettings = MapSettings(),
        var config: GitConfig = sampleConfig,
        var online: Boolean = true,
        var now: Long = 1_000_000L,
        var appOwned: (RepoRoot) -> Boolean = { true },
        val editLock: EditLock = EditLock(),
        val busy: GitSyncBusyCounter = GitSyncBusyCounter(),
        var vault: CredentialAccess? = null,
    ) {
        fun confirmFirstSync() = FirstSyncConfirmation(settings).confirm("test-graph", config.remoteName, config.remoteBranch)

        fun service(): GitSyncService {
            val fs = StubFileSystem()
            return GitSyncService(
                gitRepository = spy,
                graphLoader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository()),
                graphWriter = GraphWriter(fileSystem = fs),
                editLock = editLock,
                configRepository = StubConfigRepository(config.right()),
                networkMonitor = NetworkMonitor(),
                fileSystem = fs,
                credentialAccessProvider = vault?.let { v -> { v } },
                graphId = "test-graph",
                settings = settings,
                gitSyncBusyCounter = busy,
                isOnline = { online },
                wallClockMs = { now },
                isAppOwnedPath = { appOwned(it) },
            )
        }
    }

    private val lockedVault = object : CredentialAccess {
        override fun retrieve(key: String): String? = null
        override fun store(key: String, value: String) = Unit
        override fun delete(key: String) = Unit
        override fun isAvailable() = false
    }

    private fun policySkip(reason: ScheduledSkipReason) = TickOutcome.Skip(SkipReason.Policy(reason))

    @Test
    fun `a held EditLock skips the tick with no repository call`() = runBlocking {
        val h = Harness().also { it.confirmFirstSync() }
        h.editLock.beginEdit()
        val svc = h.service()

        val outcome = svc.scheduled.run("test-graph")

        assertEquals(policySkip(ScheduledSkipReason.EditingInProgress), outcome)
        assertEquals(emptyList(), h.spy.calls)
        svc.shutdown()
    }

    @Test
    fun `a busy counter, a locked vault and being offline each skip with no repository call`() = runBlocking {
        val busy = Harness().also { it.confirmFirstSync(); it.busy.begin() }
        val busySvc = busy.service()
        assertEquals(TickOutcome.Skip(SkipReason.Busy), busySvc.scheduled.run("test-graph"))
        assertEquals(emptyList(), busy.spy.calls)
        busySvc.shutdown()

        val locked = Harness(vault = lockedVault).also { it.confirmFirstSync() }
        val lockedSvc = locked.service()
        assertEquals(policySkip(ScheduledSkipReason.VaultLocked), lockedSvc.scheduled.run("test-graph"))
        assertEquals(emptyList(), locked.spy.calls)
        lockedSvc.shutdown()

        val offline = Harness(online = false).also { it.confirmFirstSync() }
        val offlineSvc = offline.service()
        assertEquals(TickOutcome.Skip(SkipReason.Offline), offlineSvc.scheduled.run("test-graph"))
        assertEquals(emptyList(), offline.spy.calls)
        offlineSvc.shutdown()
    }

    @Test
    fun `an in-memory conflict skips further ticks without touching the repository`() = runBlocking {
        val h = Harness().also { it.confirmFirstSync() }
        h.spy.remoteChanges = true
        h.spy.mergeConflicts = listOf(ConflictFile("/repo/journals/a.md", "journals/a.md", emptyList(), rawContent = "x"))
        val svc = h.service()
        assertTrue(svc.sync("test-graph").isLeft())
        assertIs<SyncState.ConflictPending>(svc.syncState.value)
        val before = h.spy.calls.toList()

        val outcome = svc.scheduled.run("test-graph")

        assertEquals(policySkip(ScheduledSkipReason.ConflictPending), outcome)
        assertEquals(before, h.spy.calls)
        svc.shutdown()
    }

    @Test
    fun `a fresh service over a MERGING repository skips with only a fetch`() = runBlocking {
        val h = Harness().also { it.confirmFirstSync() }
        h.spy.health = RepositoryHealth(false, "MERGING")
        val svc = h.service()

        val outcome = svc.scheduled.run("test-graph")

        assertEquals(policySkip(ScheduledSkipReason.RepoState), outcome)
        assertEquals(listOf("health", "fetch"), h.spy.calls)
        assertEquals(SyncState.Error(DomainError.GitError.RepairNeeded("MERGING")), svc.syncState.value)
        svc.shutdown()
    }

    @Test
    fun `a fetch error while skipping for repo state is reported as a failure`() = runBlocking {
        val h = Harness().also { it.confirmFirstSync() }
        h.spy.health = RepositoryHealth(false, "MERGING")
        h.spy.fetchResult = DomainError.GitError.FetchFailed("boom").left()
        val svc = h.service()

        assertIs<TickOutcome.Failure>(svc.scheduled.run("test-graph"))
        svc.shutdown()
    }

    @Test
    fun `an unconfirmed first sync fetches only and never merges commits or pushes`() = runBlocking {
        val h = Harness()
        h.spy.dirty = true
        val svc = h.service()

        val outcome = svc.scheduled.run("test-graph")

        assertEquals(policySkip(ScheduledSkipReason.FirstSyncUnconfirmed), outcome)
        assertEquals(emptyList(), h.spy.mutatingCalls())
        assertEquals(1, h.spy.calls.count { it == "fetch" })
        svc.shutdown()
    }

    @Test
    fun `a confirmation stored by an earlier build is read unchanged and the next tick runs`() = runBlocking {
        val h = Harness()
        h.settings.putString("git_first_sync_confirmed_test-graph", "origin/main")
        val svc = h.service()

        assertEquals(TickOutcome.Success, svc.scheduled.run("test-graph"))
        assertTrue("push" in h.spy.calls)
        svc.shutdown()
    }

    @Test
    fun `changing the branch re-arms the first sync gate`() = runBlocking {
        val h = Harness().also { it.confirmFirstSync() }
        h.config = h.config.copy(remoteBranch = "master")
        val svc = h.service()

        assertEquals(policySkip(ScheduledSkipReason.FirstSyncUnconfirmed), svc.scheduled.run("test-graph"))
        assertEquals(emptyList(), h.spy.mutatingCalls())
        svc.shutdown()
    }

    @Test
    fun `a pending first-sync review blocks even a matching confirmation`() = runBlocking {
        val h = Harness().also { it.confirmFirstSync() }
        FirstSyncConfirmation(h.settings).markReviewPending("test-graph", "main")
        val svc = h.service()

        assertEquals(policySkip(ScheduledSkipReason.FirstSyncUnconfirmed), svc.scheduled.run("test-graph"))
        svc.shutdown()
    }

    @Test
    fun `a SAF graph, or one whose predicate throws, gets exactly one fetch`() = runBlocking {
        for (predicate in listOf<(RepoRoot) -> Boolean>({ false }, { error("unresolvable saf uri") })) {
            val h = Harness(appOwned = predicate).also { it.confirmFirstSync() }
            h.spy.dirty = true
            h.spy.remoteChanges = true
            val svc = h.service()

            assertEquals(TickOutcome.Success, svc.scheduled.run("test-graph"))

            assertEquals(1, h.spy.calls.count { it == "fetch" })
            assertEquals(emptyList(), h.spy.mutatingCalls())
            assertEquals(SyncState.MergeAvailable(2), svc.syncState.value)
            svc.shutdown()
        }
    }

    @Test
    fun `the default path predicate treats saf and content uris as not app-owned`() = runBlocking {
        for (root in listOf("saf://tree/notes", "content://com.android/tree/x")) {
            val h = Harness(config = sampleConfig.copy(repoRoot = RepoRoot(root))).also { it.confirmFirstSync() }
            val svc = GitSyncService(
                gitRepository = h.spy,
                graphLoader = GraphLoader(StubFileSystem(), InMemoryPageRepository(), InMemoryBlockRepository()),
                graphWriter = GraphWriter(fileSystem = StubFileSystem()),
                editLock = EditLock(),
                configRepository = StubConfigRepository(h.config.right()),
                networkMonitor = NetworkMonitor(),
                fileSystem = StubFileSystem(),
                graphId = "test-graph",
                settings = h.settings,
                isOnline = { true },
            )
            svc.scheduled.run("test-graph")
            assertEquals(emptyList(), h.spy.mutatingCalls(), "root $root")
            svc.shutdown()
        }
    }

    @Test
    fun `all clear runs sync once and merges the remote commits`() = runBlocking {
        val h = Harness().also { it.confirmFirstSync() }
        h.spy.remoteChanges = true
        val svc = h.service()

        assertEquals(TickOutcome.Success, svc.scheduled.run("test-graph"))

        assertEquals(1, h.spy.calls.count { it == "fetch" })
        assertEquals(1, h.spy.calls.count { it == "merge" })
        assertEquals(1, h.spy.calls.count { it == "push" })
        assertIs<SyncState.Success>(svc.syncState.value)
        assertEquals(2, (svc.syncState.value as SyncState.Success).remoteCommitsMerged)
        svc.shutdown()
    }

    @Test
    fun `autoCommit off with a dirty tree fetches only and commits nothing`() = runBlocking {
        val h = Harness(config = sampleConfig.copy(autoCommit = false)).also { it.confirmFirstSync() }
        h.spy.dirty = true
        val svc = h.service()

        assertEquals(TickOutcome.Success, svc.scheduled.run("test-graph"))

        assertEquals(emptyList(), h.spy.mutatingCalls())
        svc.shutdown()
    }

    @Test
    fun `autoCommit off with a clean tree still syncs`() = runBlocking {
        val h = Harness(config = sampleConfig.copy(autoCommit = false)).also { it.confirmFirstSync() }
        val svc = h.service()

        assertEquals(TickOutcome.Success, svc.scheduled.run("test-graph"))
        assertTrue("push" in h.spy.calls)
        svc.shutdown()
    }

    @Test
    fun `a failed sync backs off the next ticks and the delay doubles`() = runBlocking {
        val h = Harness().also { it.confirmFirstSync() }
        h.spy.pushResult = DomainError.GitError.PushFailed("rejected").left()
        val svc = h.service()

        assertIs<TickOutcome.Failure>(svc.scheduled.run("test-graph"))
        val pushesAfterFirst = h.spy.calls.count { it == "push" }

        h.now += 30_000
        assertEquals(policySkip(ScheduledSkipReason.Backoff), svc.scheduled.run("test-graph"))
        assertEquals(pushesAfterFirst, h.spy.calls.count { it == "push" })

        h.now += 31_000
        assertIs<TickOutcome.Failure>(svc.scheduled.run("test-graph"))
        h.now += 61_000
        assertEquals(policySkip(ScheduledSkipReason.Backoff), svc.scheduled.run("test-graph"))

        h.spy.pushResult = Unit.right()
        h.now += 61_000
        assertEquals(TickOutcome.Success, svc.scheduled.run("test-graph"))
        h.now += 1
        assertEquals(TickOutcome.Success, svc.scheduled.run("test-graph"))
        svc.shutdown()
    }

    @Test
    fun `a scheduled run can never lift the mass-change guard`() = runBlocking {
        val h = Harness().also { it.confirmFirstSync() }
        h.spy.dirty = true
        h.spy.deletions = DeletionSummary((1..40).map { "notes/$it.md" }, previousTreeSize = 100)
        val svc = h.service()

        val outcome = svc.scheduled.run("test-graph")

        val failure = assertIs<TickOutcome.Failure>(outcome)
        assertIs<DomainError.GitError.MassChangeBlocked>(failure.error)
        assertEquals(emptyList(), h.spy.mutatingCalls().filter { it == "commit" || it == "push" })
        svc.shutdown()
    }

    @Test
    fun `a missing git config falls back to a harmless fetch tick`() = runBlocking {
        val spy = Spy()
        val fs = StubFileSystem()
        val svc = GitSyncService(
            gitRepository = spy,
            graphLoader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository()),
            graphWriter = GraphWriter(fileSystem = fs),
            editLock = EditLock(),
            configRepository = StubConfigRepository(Either.Right(null)),
            networkMonitor = NetworkMonitor(),
            fileSystem = fs,
            graphId = "test-graph",
            isOnline = { true },
        )

        assertEquals(TickOutcome.Success, svc.scheduled.run("test-graph"))
        assertEquals(emptyList(), spy.calls)
        svc.shutdown()
    }
}
