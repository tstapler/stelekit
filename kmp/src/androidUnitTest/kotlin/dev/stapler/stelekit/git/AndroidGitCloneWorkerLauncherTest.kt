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
        ) {}

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
        ) {}

        assertIs<Either.Left<DomainError.GitError>>(result)
        Unit
    }
}
