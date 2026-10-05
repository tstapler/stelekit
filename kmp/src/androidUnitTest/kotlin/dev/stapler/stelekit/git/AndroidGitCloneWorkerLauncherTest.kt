// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests [AndroidGitCloneWorkerLauncher] (git-sync-resilience Story 3.1.3, Task 3.1.3b) against a
 * real Robolectric-backed `WorkManager` test driver ([WorkManagerTestInitHelper]), matching
 * `WorkManagerSyncSchedulerRetryOwnerTest.kt`'s established pattern. A custom [WorkerFactory]
 * intercepts the `GitCloneWorker` class name and substitutes a trivial stand-in [Worker] whose
 * `Result` is configured per test — this isolates what `AndroidGitCloneWorkerLauncher` itself does
 * (enqueue + observe `WorkInfo` until terminal, translate to `Either`) from `GitCloneWorker`'s own
 * behavior, which `GitCloneWorkerTest.kt` already covers directly. A real `GitCloneWorker` would
 * attempt a genuine JGit clone against an unroutable host and only fail after
 * `RetryPolicies.gitTransportTransient`'s real ~31s of backoff — unsuitable for a fast unit test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidGitCloneWorkerLauncherTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        CredentialStore.init(context)
    }

    private fun initTestWorkManager(result: () -> ListenableWorker.Result) {
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker? {
                    if (workerClassName != GitCloneWorker::class.java.name) return null
                    return object : Worker(appContext, workerParameters) {
                        override fun doWork(): Result = result()
                    }
                }
            })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun `launchClone() enqueues a GitCloneWorker one-off request and suspends until WorkInfo reaches SUCCEEDED`() = runBlocking {
        initTestWorkManager { ListenableWorker.Result.success() }
        val launcher = AndroidGitCloneWorkerLauncher(context)

        val result = launcher.launchClone(
            graphId = "graph-1",
            url = "https://example.invalid/graph.git",
            localPath = "/tmp/graph",
            auth = GitAuth.None,
            onProgress = {},
            onStateChange = {},
            graphDisplayName = null,
        )

        assertIs<Either.Right<Unit>>(result)
        Unit
    }

    @Test
    fun `launchClone() translates a WorkInfo State FAILED terminal state into Left rather than hanging or throwing`() = runBlocking {
        initTestWorkManager { ListenableWorker.Result.failure() }
        val launcher = AndroidGitCloneWorkerLauncher(context)

        val result = launcher.launchClone(
            graphId = "graph-1",
            url = "https://example.invalid/graph.git",
            localPath = "/tmp/graph",
            auth = GitAuth.None,
            onProgress = {},
            onStateChange = {},
            graphDisplayName = null,
        )

        assertIs<Either.Left<DomainError.GitError>>(result)
        Unit
    }

    @Test
    fun `launchClone() decodes an auth failure payload into AuthFailed and a NonRetryableFailure state`() = runBlocking {
        val payload = GitCloneWorkerData.encodeFailure(DomainError.GitError.AuthFailed("bad token"), NonRetryableReason.AUTH)
        initTestWorkManager { ListenableWorker.Result.failure(payload) }
        val states = mutableListOf<GitTransportRetryState>()

        val result = AndroidGitCloneWorkerLauncher(context).launchClone(
            "graph-auth", "https://example.invalid/g.git", "/tmp/g", GitAuth.None, {}, { states += it }, null,
        )

        assertEquals(DomainError.GitError.AuthFailed("bad token"), (result as Either.Left).value)
        assertEquals(listOf<GitTransportRetryState>(GitTransportRetryState.NonRetryableFailure(NonRetryableReason.AUTH)), states)
    }

    @Test
    fun `launchClone() decodes a retry-exhausted payload into RetryExhausted and an Exhausted state`() = runBlocking {
        val error = DomainError.GitError.RetryExhausted(5, DomainError.GitError.CloneFailed("net down"))
        initTestWorkManager { ListenableWorker.Result.failure(GitCloneWorkerData.encodeFailure(error, null)) }
        val states = mutableListOf<GitTransportRetryState>()

        val result = AndroidGitCloneWorkerLauncher(context).launchClone(
            "graph-exh", "https://example.invalid/g.git", "/tmp/g", GitAuth.None, {}, { states += it }, null,
        )

        val left = (result as Either.Left).value as DomainError.GitError.RetryExhausted
        assertEquals(5, left.attempts)
        assertIs<GitTransportRetryState.Exhausted>(states.single())
        assertEquals(5, (states.single() as GitTransportRetryState.Exhausted).maxAttempts)
    }

    @Test
    fun `launchClone() removes transient credentials once the clone terminates`() = runBlocking {
        initTestWorkManager { ListenableWorker.Result.success() }
        val creds = InMemoryCredentialAccess()
        val key = GitCloneWorker.httpsTokenCredentialKey("graph-cred")

        AndroidGitCloneWorkerLauncher(context, credentialAccessOverride = creds).launchClone(
            "graph-cred", "https://example.invalid/g.git", "/tmp/g",
            GitAuth.HttpsToken("", { "pat-secret" }), {}, {}, null,
        )

        assertEquals(listOf(key), creds.storedKeys, "the PAT must have been persisted for the worker")
        assertEquals(null, creds.retrieve(key))
        Unit
    }

    @Test
    fun `launchClone() displaces a never-finishing same-name predecessor via REPLACE instead of blocking behind it`() = runBlocking {
        initTestWorkManager { ListenableWorker.Result.success() }
        val graphId = "graph-blocked"
        val workManager = androidx.work.WorkManager.getInstance(context)
        val name = WorkManagerSyncScheduler.workNameFor(graphId)
        // A same-name predecessor that never finishes, which any APPEND-style enqueue would BLOCK behind.
        val blocker = androidx.work.OneTimeWorkRequestBuilder<GitSyncWorker>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.HOURS).build()
        workManager.enqueueUniqueWork(name, androidx.work.ExistingWorkPolicy.KEEP, blocker).result.get()

        val result = AndroidGitCloneWorkerLauncher(context, blockedTimeoutMs = 2_000L).launchClone(
            graphId, "https://example.invalid/g.git", "/tmp/g", GitAuth.None, {}, {}, null,
        )

        assertIs<Either.Right<Unit>>(result)
        Unit
    }

    @Test
    fun `GitCloneWorkerData round-trips every GitTransportRetryState`() {
        val states = listOf(
            GitTransportRetryState.Idle,
            GitTransportRetryState.Attempting(CloneProgress("Receiving objects", 3, 10), foregroundPromoted = false),
            GitTransportRetryState.Retrying(2, 5, CloneProgress("x", 1, 2)),
            GitTransportRetryState.Retrying(2, 5, null),
            GitTransportRetryState.ResumingDeepen(40),
            GitTransportRetryState.ResumingDeepen(null),
            GitTransportRetryState.Exhausted("net", 5),
            GitTransportRetryState.NonRetryableFailure(NonRetryableReason.NOT_FOUND),
        )
        states.forEach { assertEquals(it, GitCloneWorkerData.decodeState(GitCloneWorkerData.encodeState(it))) }
    }

    @Test
    fun `ProgressThrottle emits only on percent change or after the interval`() {
        var now = 0L
        val throttle = ProgressThrottle(500L) { now }
        assertEquals(true, throttle.shouldEmit(CloneProgress("p", 0, 100)))
        now = 10
        assertEquals(false, throttle.shouldEmit(CloneProgress("p", 0, 100)))
        assertEquals(true, throttle.shouldEmit(CloneProgress("p", 5, 100)))
        now = 600
        assertEquals(true, throttle.shouldEmit(CloneProgress("p", 5, 100)))
    }
}

internal class InMemoryCredentialAccess : dev.stapler.stelekit.platform.security.CredentialAccess {
    private val map = mutableMapOf<String, String>()
    val storedKeys = mutableListOf<String>()
    override fun retrieve(key: String): String? = map[key]
    override fun store(key: String, value: String) { map[key] = value; storedKeys += key }
    override fun delete(key: String) { map.remove(key) }
}
