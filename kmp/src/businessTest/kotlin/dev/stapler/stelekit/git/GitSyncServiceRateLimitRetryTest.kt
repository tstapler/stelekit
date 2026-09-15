// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for Task 3.4.2d (Story 3.4.2): [GitSyncService.scheduleRateLimitRetry]'s scheduling,
 * cancellation-by-[GitSyncService.shutdown], and cancellation-by-manual-trigger behavior.
 *
 * NOTE on timing / why `runBlocking` instead of `kotlinx.coroutines.test.runTest`: unlike
 * `WasmSectionSyncServiceTest`'s `TC-6.4-G` pattern — a pure-Kotlin retry *model* invoked directly
 * under `runTest`, so `kotlinx.coroutines.test`'s virtual-time scheduler governs its `delay()`
 * calls — `GitSyncService.scheduleRateLimitRetry` launches on the service's own long-lived `scope`
 * (`SupervisorJob() + PlatformDispatcher.IO`, per `CLAUDE.md`'s "never accept
 * rememberCoroutineScope()" rule). `PlatformDispatcher.IO` resolves to a fixed `Dispatchers.IO`
 * value on the JVM (`PlatformDispatcher.jvm.kt`) — it is not swappable for a `TestDispatcher`, so
 * its `delay()` calls run in real wall-clock time regardless of what dispatcher the *test*
 * coroutine happens to run on. Using `runTest` here would let the test's own `delay()` calls
 * auto-advance a virtual clock that has no relationship to the real background delay actually
 * elapsing, producing either a false pass or a busy-spin. These tests therefore use `runBlocking`
 * with short *real* delays (1 second) so every `delay()` call — test and production alike — means
 * the same thing.
 */
class GitSyncServiceRateLimitRetryTest {

    private open class StubFileSystem : FileSystem {
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

    /** Strict [GitRepository] stub — each test overrides only the methods its scenario exercises. */
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

    private class StubConfigRepository(
        private val configResult: Either<DomainError, GitConfig?>,
    ) : GitConfigRepository {
        override suspend fun getConfig(graphId: String): Either<DomainError, GitConfig?> = configResult
        override suspend fun saveConfig(config: GitConfig): Either<DomainError, Unit> = Unit.right()
        override suspend fun deleteConfig(graphId: String): Either<DomainError, Unit> = Unit.right()
        override fun observeConfig(graphId: String): Flow<Either<DomainError, GitConfig?>> = flowOf(configResult)
    }

    private val sampleConfig = GitConfig(
        graphId = "test-graph",
        repoRoot = "/repo",
        wikiSubdir = "",
        authType = GitAuthType.NONE,
    )

    private fun buildService(gitRepository: GitRepository): GitSyncService {
        val stubFs = StubFileSystem()
        val graphLoader = GraphLoader(
            fileSystem = stubFs,
            pageRepository = InMemoryPageRepository(),
            blockRepository = InMemoryBlockRepository(),
        )
        val graphWriter = GraphWriter(fileSystem = stubFs)
        return GitSyncService(
            gitRepository = gitRepository,
            graphLoader = graphLoader,
            graphWriter = graphWriter,
            editLock = EditLock(),
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
            networkMonitor = NetworkMonitor(),
            fileSystem = stubFs,
        )
    }

    private fun requireOnline() = assumeTrue(
        "Skipped: requires network access for the initial NetworkMonitor.isOnline check",
        NetworkMonitor().isOnline,
    )

    // ── (a)/(b): no re-invocation before the delay elapses; exactly one re-invocation after ──

    @Test
    fun `scheduleRateLimitRetry does not re-invoke fetchOnly before retryAfterSeconds elapses, then fires exactly once`() {
        requireOnline()
        runBlocking {
            var fetchCallCount = 0
            val repo = object : StubGitRepository() {
                override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
                    fetchCallCount++
                    return if (fetchCallCount == 1) {
                        DomainError.GitError.RateLimited(retryAfterSeconds = 1).left()
                    } else {
                        // A real suspension point here (unlike a synchronous return) is what
                        // actually exercises scheduleRateLimitRetry's self-cancellation footgun:
                        // the scheduled job invokes fetchOnly(), whose own cancel-at-top guard
                        // would previously cancel its OWN still-running job if rateLimitRetryJob
                        // hadn't been cleared first — the cancellation only manifests at the next
                        // real suspension point, which a non-suspending stub body would never hit.
                        delay(1)
                        FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()
                    }
                }
            }
            val service = buildService(repo)

            service.fetchOnly("test-graph")
            assertEquals(1, fetchCallCount)
            assertTrue(service.syncState.value is SyncState.RateLimited)

            // Well before the 1s scheduled retry, no re-invocation should have happened yet.
            delay(300)
            assertEquals(1, fetchCallCount, "must not re-invoke fetchOnly before the stated delay elapses")

            // After the delay elapses, exactly one retry should fire.
            withTimeout(5_000) {
                while (fetchCallCount < 2) delay(50)
            }
            assertEquals(2, fetchCallCount, "must re-invoke fetchOnly exactly once after the delay")

            // Confirm it doesn't fire again.
            delay(300)
            assertEquals(2, fetchCallCount, "must not fire more than once for a single scheduled retry")

            service.shutdown()
        }
    }

    // ── (c): shutdown() cancels the pending scheduled retry ─────────────────────────────────

    @Test
    fun `shutdown cancels a pending scheduled retry before it fires`() {
        requireOnline()
        runBlocking {
            var fetchCallCount = 0
            val repo = object : StubGitRepository() {
                override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
                    fetchCallCount++
                    return DomainError.GitError.RateLimited(retryAfterSeconds = 1).left()
                }
            }
            val service = buildService(repo)

            service.fetchOnly("test-graph")
            assertEquals(1, fetchCallCount)

            service.shutdown()

            // Wait well past the 1s delay window — shutdown must have cancelled the scheduled retry.
            delay(1_500)
            assertEquals(1, fetchCallCount, "shutdown must cancel the pending scheduled retry")
        }
    }

    // ── (d): a manual trigger before the delay elapses cancels the pending scheduled retry ──

    @Test
    fun `a manual fetchOnly call before the delay elapses cancels the pending scheduled retry - no double-fire`() {
        requireOnline()
        runBlocking {
            var fetchCallCount = 0
            val repo = object : StubGitRepository() {
                override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
                    fetchCallCount++
                    // First call: rate limited (schedules a retry). Every subsequent call: success.
                    return if (fetchCallCount == 1) {
                        DomainError.GitError.RateLimited(retryAfterSeconds = 1).left()
                    } else {
                        FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()
                    }
                }
            }
            val service = buildService(repo)

            service.fetchOnly("test-graph")
            assertEquals(1, fetchCallCount)

            // Manual trigger before the 1s scheduled retry fires — must cancel the pending job.
            delay(200)
            service.fetchOnly("test-graph")
            assertEquals(2, fetchCallCount, "the manual call itself must have invoked fetch")

            // Wait past the original scheduled-retry window — it must NOT also fire (no double-fire).
            delay(1_200)
            assertEquals(2, fetchCallCount, "the superseded scheduled retry must never fire")

            service.shutdown()
        }
    }

    // ── sync()'s RateLimited branch also schedules a retry, wired to ::sync (not ::fetchOnly) ──

    @Test
    fun `sync when commit returns RateLimited schedules a retry that re-invokes sync, reaching commit again`() {
        requireOnline()
        runBlocking {
            var commitCallCount = 0
            val repo = object : StubGitRepository() {
                override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
                    GitStatus(hasLocalChanges = true, untrackedFiles = emptyList(), modifiedFiles = listOf("pages/foo.md")).right()

                override suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit> = Unit.right()

                override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> {
                    commitCallCount++
                    return DomainError.GitError.RateLimited(retryAfterSeconds = 1).left()
                }
            }
            val service = buildService(repo)

            service.sync("test-graph")
            assertEquals(1, commitCallCount)

            withTimeout(5_000) {
                while (commitCallCount < 2) delay(50)
            }
            assertEquals(2, commitCallCount, "the scheduled retry must re-invoke sync(), reaching commit() again")

            service.shutdown()
        }
    }
}
