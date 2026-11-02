// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Repository-state, conflict-marker and mass-change guards on every commit and push path (dev plan Tasks 2.3e, 2.3f). */
class GitSyncServiceChangeGuardsTest {

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private class Spy(
        var health: RepositoryHealth = RepositoryHealth.Clean,
        val markers: MutableMap<GuardScope, MarkerScan> = mutableMapOf(),
        val deletions: MutableMap<GuardScope, DeletionSummary> = mutableMapOf(),
        var dirty: Boolean = true,
    ) : StubGitRepository() {
        val calls = mutableListOf<String>()

        override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
            GitStatus(dirty, emptyList(), if (dirty) listOf("journals/2026_10_08.md") else emptyList()).right()

        override suspend fun repositoryHealth(config: GitConfig) = health
        override suspend fun scanForConflictMarkers(config: GitConfig, scope: GuardScope) = markers[scope] ?: MarkerScan.Clean
        override suspend fun deletionSummary(config: GitConfig, scope: GuardScope) = deletions[scope] ?: DeletionSummary.None

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
            return FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()
        }

        override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> {
            calls += "merge"
            return MergeResult(false, emptyList(), emptyList()).right()
        }

        override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> {
            calls += "push"
            return Unit.right()
        }

        override suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide) = Unit.right()
        override suspend fun markResolved(config: GitConfig, filePath: String) = Unit.right()
    }

    private fun service(spy: Spy, settings: Settings? = null): GitSyncService {
        val fs = StubFileSystem()
        return GitSyncService(
            gitRepository = spy,
            graphLoader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository()),
            graphWriter = GraphWriter(fileSystem = fs),
            editLock = EditLock(),
            configRepository = StubConfigRepository(sampleConfig.right()),
            networkMonitor = NetworkMonitor(),
            fileSystem = fs,
            graphId = "test-graph",
            settings = settings,
            isOnline = { true },
        )
    }

    private fun deletions(count: Int, treeSize: Int, prefix: String = "notes/") =
        DeletionSummary((1..count).map { "$prefix$it.md" }, treeSize)

    private val mutating = setOf("stage", "commit", "merge", "push")

    @Test
    fun `sync in MERGING state returns RepairNeeded before any stage commit merge or push`() = runBlocking {
        val spy = Spy(health = RepositoryHealth(false, "MERGING"))
        val svc = service(spy)

        val result = svc.sync("test-graph")

        assertEquals(DomainError.GitError.RepairNeeded("MERGING"), (result as Either.Left).value)
        assertEquals(emptyList(), spy.calls.filter { it in mutating })
        assertIs<SyncState.Error>(svc.syncState.value)
        svc.shutdown()
    }

    @Test
    fun `a service built over an unsafe repository refuses even as a fresh instance`() = runBlocking {
        val spy = Spy(health = RepositoryHealth(false, "UNMERGED_PATHS"))
        val first = service(spy).also { it.shutdown() }
        val fresh = service(spy)

        assertEquals(DomainError.GitError.RepairNeeded("UNMERGED_PATHS"), (fresh.sync("test-graph") as Either.Left).value)
        assertTrue(first.syncState.value !is SyncState.Success)
        fresh.shutdown()
    }

    @Test
    fun `staged conflict markers refuse the commit and nothing is committed or pushed`() = runBlocking {
        val spy = Spy(markers = mutableMapOf(GuardScope.Staged to MarkerScan.Found(listOf("journals/2026_10_08.md"))))
        val svc = service(spy)

        val result = svc.sync("test-graph")

        assertEquals(
            DomainError.GitError.ConflictMarkersPresent(listOf("journals/2026_10_08.md")),
            (result as Either.Left).value,
        )
        assertEquals(listOf("stage"), spy.calls.filter { it in mutating })
        svc.shutdown()
    }

    @Test
    fun `an incomplete marker scan fails closed`() = runBlocking {
        val spy = Spy(markers = mutableMapOf(GuardScope.Staged to MarkerScan.Incomplete("big.md is larger than 2048 KiB")))
        val svc = service(spy)

        assertIs<DomainError.GitError.ScanIncomplete>((svc.sync("test-graph") as Either.Left).value)
        assertTrue("commit" !in spy.calls)
        svc.shutdown()
    }

    @Test
    fun `markers in an existing local commit refuse the push even with a clean tree`() = runBlocking {
        val spy = Spy(dirty = false, markers = mutableMapOf(GuardScope.NetPush to MarkerScan.Found(listOf("a.md"))))
        val svc = service(spy)

        val result = svc.sync("test-graph")

        assertIs<DomainError.GitError.ConflictMarkersPresent>((result as Either.Left).value)
        assertTrue("push" !in spy.calls, "push must not run: ${spy.calls}")
        svc.shutdown()
    }

    @Test
    fun `a clean scan lets sync commit and push`() = runBlocking {
        val spy = Spy()
        val svc = service(spy)

        assertTrue(svc.sync("test-graph").isRight())
        assertEquals(listOf("stage", "commit", "fetch", "push"), spy.calls)
        svc.shutdown()
    }

    @Test
    fun `mass deletion above the default threshold blocks the commit`() = runBlocking {
        val spy = Spy(deletions = mutableMapOf(GuardScope.Staged to deletions(21, treeSize = 100)))
        val svc = service(spy)

        val blocked = (svc.sync("test-graph") as Either.Left).value
        assertIs<DomainError.GitError.MassChangeBlocked>(blocked)
        assertEquals(21, blocked.deleted)
        assertEquals(20, blocked.threshold)
        assertTrue("commit" !in spy.calls)
        svc.shutdown()
    }

    @Test
    fun `mass deletion at the push step blocks the push`() = runBlocking {
        val spy = Spy(deletions = mutableMapOf(GuardScope.NetPush to deletions(30, treeSize = 100)))
        val svc = service(spy)

        assertIs<DomainError.GitError.MassChangeBlocked>((svc.sync("test-graph") as Either.Left).value)
        assertTrue("push" !in spy.calls)
        svc.shutdown()
    }

    @Test
    fun `deletions within the threshold pass`() = runBlocking {
        val spy = Spy(deletions = mutableMapOf(GuardScope.Staged to deletions(20, treeSize = 100)))
        val svc = service(spy)

        assertTrue(svc.sync("test-graph").isRight())
        svc.shutdown()
    }

    @Test
    fun `the threshold scales with the previous tree size`() {
        assertNull(MassChangeThresholds().evaluate(deletions(200, treeSize = 4000)))
        assertEquals(201, MassChangeThresholds().evaluate(deletions(201, treeSize = 4000))?.deleted)
    }

    @Test
    fun `more than ten journal deletions block even under the file limit`() {
        val blocked = MassChangeThresholds().evaluate(deletions(11, treeSize = 4000, prefix = "journals/"))

        assertEquals(11, blocked?.deleted)
        assertEquals(10, blocked?.threshold)
    }

    @Test
    fun `overridden thresholds change the verdict in both directions`() = runBlocking {
        val lowered = MapSettings().apply { putString(MassChangeThresholds.KEY_MIN_FILES, "3"); putString(MassChangeThresholds.KEY_PERCENT, "1") }
        val blockedSpy = Spy(deletions = mutableMapOf(GuardScope.Staged to deletions(5, treeSize = 100)))
        val blockedSvc = service(blockedSpy, lowered)
        assertIs<DomainError.GitError.MassChangeBlocked>((blockedSvc.sync("test-graph") as Either.Left).value)
        blockedSvc.shutdown()

        val raised = MapSettings().apply { putString(MassChangeThresholds.KEY_MIN_FILES, "40") }
        val passSpy = Spy(deletions = mutableMapOf(GuardScope.Staged to deletions(25, treeSize = 100)))
        val passSvc = service(passSpy, raised)
        assertTrue(passSvc.sync("test-graph").isRight())
        passSvc.shutdown()
    }

    @Test
    fun `unreadable or non-positive overrides fall back to the defaults`() {
        val settings = MapSettings().apply {
            putString(MassChangeThresholds.KEY_MIN_FILES, "banana")
            putString(MassChangeThresholds.KEY_PERCENT, "0")
        }
        assertEquals(MassChangeThresholds(), MassChangeThresholds.from(settings))
    }

    @Test
    fun `a foreground confirm lifts the mass-change guard once and a scheduled run cannot`() = runBlocking {
        val spy = Spy(deletions = mutableMapOf(GuardScope.Staged to deletions(40, treeSize = 100)))
        val svc = service(spy)

        val automatic = svc.sync("test-graph", SyncTrigger.Automatic, liftMassChangeGuard = true)
        assertIs<DomainError.GitError.MassChangeBlocked>((automatic as Either.Left).value)
        assertTrue("commit" !in spy.calls)

        assertIs<DomainError.GitError.MassChangeBlocked>((svc.sync("test-graph") as Either.Left).value)
        assertTrue(svc.sync("test-graph", SyncTrigger.Manual, liftMassChangeGuard = true).isRight())
        assertTrue("commit" in spy.calls)
        svc.shutdown()
    }

    @Test
    fun `commitLocalChanges is guarded by state markers and mass change`() = runBlocking {
        val unsafe = Spy(health = RepositoryHealth(false, "MERGING"))
        val unsafeSvc = service(unsafe)
        assertIs<DomainError.GitError.RepairNeeded>((unsafeSvc.commitLocalChanges("test-graph") as Either.Left).value)
        assertTrue("commit" !in unsafe.calls)
        unsafeSvc.shutdown()

        val markered = Spy(markers = mutableMapOf(GuardScope.Staged to MarkerScan.Found(listOf("x.md"))))
        val markeredSvc = service(markered)
        assertIs<DomainError.GitError.ConflictMarkersPresent>((markeredSvc.commitLocalChanges("test-graph") as Either.Left).value)
        assertTrue("commit" !in markered.calls)
        markeredSvc.shutdown()

        val mass = Spy(deletions = mutableMapOf(GuardScope.Staged to deletions(50, treeSize = 100)))
        val massSvc = service(mass)
        assertIs<DomainError.GitError.MassChangeBlocked>((massSvc.commitLocalChanges("test-graph") as Either.Left).value)
        assertTrue("commit" !in mass.calls)
        massSvc.shutdown()
    }

    @Test
    fun `resolveConflicts refuses to commit staged markers and still runs in MERGING`() = runBlocking {
        val spy = Spy(health = RepositoryHealth(false, "MERGING"), markers = mutableMapOf(GuardScope.Staged to MarkerScan.Found(listOf("x.md"))))
        val svc = service(spy)

        val result = svc.resolveConflicts("test-graph", emptyList())

        assertIs<DomainError.GitError.ConflictMarkersPresent>((result as Either.Left).value)
        assertTrue("commit" !in spy.calls)
        svc.shutdown()
    }

    @Test
    fun `resolveConflicts commits in MERGING when the staged diff is clean`() = runBlocking {
        val spy = Spy(health = RepositoryHealth(false, "MERGING"))
        val svc = service(spy)

        assertTrue(svc.resolveConflicts("test-graph", emptyList()).isRight())
        assertTrue("commit" in spy.calls)
        svc.shutdown()
    }

    @Test
    fun `applyJournalMerge guards the commit and the push`() = runBlocking {
        val commitBlocked = Spy(markers = mutableMapOf(GuardScope.Staged to MarkerScan.Found(listOf("j.md"))))
        val commitSvc = service(commitBlocked)
        assertIs<DomainError.GitError.ConflictMarkersPresent>(
            (commitSvc.applyJournalMerge("test-graph", "/repo/journals/j.md", "- merged\n") as Either.Left).value,
        )
        assertTrue("commit" !in commitBlocked.calls)
        commitSvc.shutdown()

        val pushBlocked = Spy(deletions = mutableMapOf(GuardScope.NetPush to deletions(40, treeSize = 100)))
        val pushSvc = service(pushBlocked)
        assertIs<DomainError.GitError.MassChangeBlocked>(
            (pushSvc.applyJournalMerge("test-graph", "/repo/journals/j.md", "- merged\n") as Either.Left).value,
        )
        assertTrue("push" !in pushBlocked.calls)
        pushSvc.shutdown()
    }
}
