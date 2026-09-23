// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.AndroidGraphMoveQuiesceStrategy
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CRITICAL finding (PR #327 review): [GitSyncService] and [AndroidGraphMoveQuiesceStrategy] must
 * share the exact same [GitSyncBusyCounter] instance — otherwise `quiesce()`'s `awaitIdle()` never
 * observes a real `sync()` in flight, and a relocate can copy `.git` mid-sync (the torn-write race
 * this machinery exists to prevent). This test proves the sharing is real (same object observed
 * across both call paths), not merely that both sides construct *a* counter: it increments the
 * counter from [GitSyncService.sync]'s own code path and asserts
 * [AndroidGraphMoveQuiesceStrategy.quiesce] — driven purely by the injected counter, with no
 * shadow-worktree target — blocks until that same `sync()` call decrements it.
 *
 * Uses [runBlocking] with [CompletableDeferred] signals, not `runTest`/virtual time:
 * `GitSyncService.sync()` always redispatches onto the real `PlatformDispatcher.IO`
 * (`Dispatchers.IO` on Android — see `PlatformDispatcher.android.kt`), so a virtual-time test
 * dispatcher can't observe its progress deterministically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GitSyncBusyCounterSharedWiringTest {

    /**
     * Fakes a validated, internet-capable active network via Robolectric's `ShadowConnectivityManager`
     * and initializes [NetworkMonitor] with the Robolectric application context, so
     * [NetworkMonitor.isOnline] deterministically reports `true` regardless of the host machine's
     * real connectivity — [NetworkMonitor.android.kt]'s real implementation otherwise always
     * reports offline in a bare Robolectric test (no `Application.onCreate()` ever calls
     * `NetworkMonitor.init`).
     */
    private fun fakeOnlineNetworkMonitor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NetworkMonitor.init(context)
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowConnectivityManager = shadowOf(connectivityManager)
        val networkInfo = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI,
            0,
            true,
            true,
        )
        shadowConnectivityManager.setActiveNetworkInfo(networkInfo)
        val activeNetwork = connectivityManager.activeNetwork ?: return
        val capabilities = NetworkCapabilities()
        shadowOf(capabilities).apply {
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        shadowConnectivityManager.setNetworkCapabilities(activeNetwork, capabilities)
    }

    /** Minimal [FileSystem] stub — safe defaults for all methods, mirrors GitSyncServiceTest's. */
    private class StubFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/tmp"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String): Boolean = true
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
     * [GitRepository] stub whose [hasDetachedHead] signals [entered] (proving `sync()` reached
     * this point — and therefore already called `gitSyncBusyCounter.begin()`, which happens
     * earlier in the same sequential pipeline) and then suspends on [gate] until released, before
     * returning `true`. Releasing [gate] cleanly ends `sync()` via its existing DetachedHead error
     * path (no further stubbing needed for commit/fetch/merge/push) while `finally` still
     * decrements the shared busy counter, exactly as a real in-flight sync would.
     */
    private class GateGitRepository(
        private val entered: CompletableDeferred<Unit>,
        private val gate: CompletableDeferred<Unit>,
    ) : GitRepository {
        override suspend fun isGitRepo(path: String): Boolean = error("not needed in this test")
        override suspend fun init(repoRoot: String): Either<DomainError.GitError, Unit> = error("not needed in this test")
        override suspend fun clone(url: String, localPath: String, auth: GitAuth, onProgress: (String) -> Unit): Either<DomainError.GitError, Unit> = error("not needed in this test")
        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> = error("not needed in this test")
        override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> = error("not needed in this test")
        override suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit> = error("not needed in this test")
        override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> = error("not needed in this test")
        override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> = error("not needed in this test")
        override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> = error("not needed in this test")
        override suspend fun log(config: GitConfig, maxCount: Int): Either<DomainError.GitError, List<GitCommit>> = error("not needed in this test")
        override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> = error("not needed in this test")
        override suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide): Either<DomainError.GitError, Unit> = error("not needed in this test")
        override suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit> = error("not needed in this test")
        override suspend fun removeStaleLockFile(config: GitConfig): Either<DomainError.GitError, Unit> = Unit.right()

        override suspend fun hasDetachedHead(config: GitConfig): Boolean {
            entered.complete(Unit)
            gate.await()
            return true
        }
    }

    /** [GitConfigRepository] returning a fixed valid config — reaches [GitRepository.hasDetachedHead]. */
    private class FixedConfigRepository(private val config: GitConfig) : GitConfigRepository {
        override suspend fun getConfig(graphId: String): Either<DomainError, GitConfig?> = config.right()
        override suspend fun saveConfig(config: GitConfig): Either<DomainError, Unit> = Unit.right()
        override suspend fun deleteConfig(graphId: String): Either<DomainError, Unit> = Unit.right()
        override fun observeConfig(graphId: String) = error("not needed in this test")
    }

    private val sampleConfig = GitConfig(
        graphId = "test-graph",
        repoRoot = "/repo",
        wikiSubdir = "",
        authType = GitAuthType.NONE,
    )

    private val op: StorageMoveOperation = StorageMoveOperation.Relocate(
        graphId = "test-graph",
        source = StorageLocation.SafFolder("test-graph", "content://tree/x"),
        destination = StorageLocation.AppOwned("test-graph"),
        deleteSourceAfterVerify = true,
    )

    @Test
    fun quiesce_should_ObserveBusyState_When_SameGitSyncBusyCounterDrivesRealGitSyncServiceSync() = runBlocking {
        fakeOnlineNetworkMonitor()

        // The instance under test — must be the SAME object threaded into both construction
        // sites, mirroring MainActivity.kt's sharedGitSyncBusyCounter wiring.
        val sharedCounter = GitSyncBusyCounter()

        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val stubFs = StubFileSystem()
        val service = GitSyncService(
            gitRepository = GateGitRepository(entered, gate),
            graphLoader = GraphLoader(
                fileSystem = stubFs,
                pageRepository = InMemoryPageRepository(),
                blockRepository = InMemoryBlockRepository(),
            ),
            graphWriter = GraphWriter(fileSystem = stubFs),
            editLock = EditLock(),
            configRepository = FixedConfigRepository(sampleConfig),
            networkMonitor = NetworkMonitor(),
            fileSystem = stubFs,
            gitSyncBusyCounter = sharedCounter,
        )

        val strategy = AndroidGraphMoveQuiesceStrategy(
            gitSyncBusyCounter = sharedCounter,
            // No shadow-worktree target — isolates the assertion to busy-counter wiring; see
            // MainActivity.kt's TODO for the still-unwired shadowWorktreeTarget half.
            shadowWorktreeTarget = { null },
        )

        assertTrue(!sharedCounter.isBusy.value, "precondition: counter starts idle")

        val syncJob = launch { service.sync("test-graph") }
        withTimeout(5_000) { entered.await() }
        assertTrue(sharedCounter.isBusy.value, "sync() must mark the shared counter busy while in flight")

        var quiesceCompleted = false
        val quiesceJob = launch {
            strategy.quiesce(op)
            quiesceCompleted = true
        }
        // gate is still unreleased, so sync() cannot have finished yet — quiesce() genuinely
        // cannot complete within this window unless it's ignoring the busy counter entirely.
        val completedEarly = withTimeoutOrNull(500) { quiesceJob.join() }
        assertNull(completedEarly, "quiesce() must not complete while GitSyncService.sync() is in flight on the SAME counter")
        assertTrue(!quiesceCompleted)

        // Release sync()'s suspension point — it completes via the DetachedHead error path,
        // decrementing the shared counter in its `finally` block exactly like a real sync.
        gate.complete(Unit)
        withTimeout(5_000) { syncJob.join() }
        withTimeout(5_000) { quiesceJob.join() }

        assertTrue(!sharedCounter.isBusy.value, "counter must be idle again once sync() finishes")
        assertTrue(quiesceCompleted, "quiesce() must complete once GitSyncService.sync() ends and decrements the SAME counter")

        strategy.release(op)
    }
}
