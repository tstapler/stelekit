// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import arrow.core.left
import arrow.core.right
import com.google.common.util.concurrent.ListenableFuture
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.testsupport.cloningStub
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests [GitCloneWorker] (git-sync-resilience Story 3.1.2/3.1.4/3.1.5) via `TestListenableWorkerBuilder`
 * with a custom [WorkerFactory] that injects [GitCloneWorker]'s `gitRepositoryOverride` test seam
 * — [cloningStub] stands in for a real [AndroidGitRepository], avoiding a real JGit/filesystem
 * setup. `setForeground()`/`setProgress()` are backed by hand-rolled [ForegroundUpdater]/
 * [ProgressUpdater] fakes (not Guava's `Futures.immediateFuture`, which isn't on this module's
 * classpath — only the `listenablefuture` stub interface jar is) so the real `ForegroundInfo`
 * reaches `doWork()`'s `setForeground()` call without needing a fully initialized `WorkManager`
 * instance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31]) // Task 3.1.2f's denial test constructs a real API-31+ exception type.
class GitCloneWorkerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class RecordingForegroundUpdater : ForegroundUpdater {
        var lastForegroundInfo: ForegroundInfo? = null
            private set

        override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> {
            lastForegroundInfo = foregroundInfo
            return immediateVoidFuture()
        }
    }

    private class DenyingForegroundUpdater(private val exception: Throwable) : ForegroundUpdater {
        override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> {
            throw exception
        }
    }

    private class NoOpProgressUpdater : ProgressUpdater {
        override fun updateProgress(context: Context, id: UUID, data: Data): ListenableFuture<Void> = immediateVoidFuture()
    }

    private fun defaultInputData(authType: String = GitCloneWorker.AUTH_NONE): Data = workDataOf(
        GitCloneWorker.KEY_URL to "https://example.invalid/graph.git",
        GitCloneWorker.KEY_LOCAL_PATH to "/tmp/graph",
        GitCloneWorker.KEY_GRAPH_ID to "graph-1",
        GitCloneWorker.KEY_AUTH_TYPE to authType,
    )

    private fun buildWorker(
        gitRepository: GitRepository,
        foregroundUpdater: ForegroundUpdater = RecordingForegroundUpdater(),
        inputData: Data = defaultInputData(),
        credentialAccess: dev.stapler.stelekit.platform.security.CredentialAccess? = null,
    ): GitCloneWorker =
        TestListenableWorkerBuilder<GitCloneWorker>(context)
            .setInputData(inputData)
            .setForegroundUpdater(foregroundUpdater)
            .setProgressUpdater(NoOpProgressUpdater())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = GitCloneWorker(appContext, workerParameters, gitRepository, credentialAccess)
            })
            .build()

    @Test
    fun `doWork() calls setForeground() with a dataSync-typed ForegroundInfo before invoking clone()`() = runBlocking {
        var setForegroundCalledBeforeClone = false
        val foregroundUpdater = RecordingForegroundUpdater()
        var cloneInvoked = false
        val repo = cloningStub { _, _, _, _ ->
            cloneInvoked = true
            setForegroundCalledBeforeClone = foregroundUpdater.lastForegroundInfo != null
            Unit.right()
        }
        val worker = buildWorker(repo, foregroundUpdater = foregroundUpdater)

        val result = worker.doWork()

        assertTrue(cloneInvoked, "clone() must be invoked")
        assertTrue(setForegroundCalledBeforeClone, "setForeground() must be called before clone()")
        val info = requireNotNull(foregroundUpdater.lastForegroundInfo) { "a ForegroundInfo must have been submitted" }
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `doWork() logs the denial, proceeds without foreground promotion, and still completes the clone when setForeground() throws ForegroundServiceStartNotAllowedException`() = runBlocking {
        var cloneInvoked = false
        val repo = cloningStub { _, _, _, _ -> cloneInvoked = true; Unit.right() }
        val denyingUpdater = DenyingForegroundUpdater(ForegroundServiceStartNotAllowedException("denied"))
        val worker = buildWorker(repo, foregroundUpdater = denyingUpdater)

        val result = worker.doWork()

        assertTrue(cloneInvoked, "denial must not short-circuit — the transfer itself continues")
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `built via TestListenableWorkerBuilder against a StubGitRepository, doWork() completes end-to-end and returns Result success`() = runBlocking {
        val worker = buildWorker(cloningStub { _, _, _, onProgress -> onProgress(CloneProgress("Receiving objects", 0, 0)); Unit.right() })

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `doWork() returns Result failure, never Result retry, when clone() returns a Left`() = runBlocking {
        val repo = cloningStub { _, _, _, _ ->
            DomainError.GitError.RetryExhausted(5, DomainError.GitError.FetchFailed("net down")).left()
        }
        val worker = buildWorker(repo)

        val result = worker.doWork()

        val failure = result as ListenableWorker.Result.Failure
        assertEquals(GitCloneWorkerData.ERROR_RETRY_EXHAUSTED, failure.outputData.getString(GitCloneWorkerData.KEY_ERROR_KIND))
    }

    @Test
    fun `doWork() returns Result failure, not Result retry, for a non-retryable Left on the very first attempt too`() = runBlocking {
        val repo = cloningStub { _, _, _, _ -> DomainError.GitError.AuthFailed("bad token").left() }
        val worker = buildWorker(repo)

        val result = worker.doWork()

        val failure = result as ListenableWorker.Result.Failure
        assertEquals(GitCloneWorkerData.ERROR_AUTH, failure.outputData.getString(GitCloneWorkerData.KEY_ERROR_KIND))
    }

    @Test
    fun `doWork() removes the transient clone credentials on every terminal path`() = runBlocking {
        val store = InMemoryCredentialAccess()
        val key = GitCloneWorker.httpsTokenCredentialKey("graph-1")
        store.store(key, "secret-pat")
        val worker = buildWorker(
            cloningStub { _, _, _, _ -> DomainError.GitError.AuthFailed("bad token").left() },
            inputData = defaultInputData(GitCloneWorker.AUTH_HTTPS_TOKEN),
            credentialAccess = store,
        )

        worker.doWork()

        assertEquals(null, store.retrieve(key), "transient PAT must be deleted after a terminal failure")
    }

    @Test
    fun `doWork() converts its foreground notification to a dismissible tap-to-retry one on a failed clone, rather than lingering as in-progress`() = runBlocking {
        // Superseded by git-sync-resilience Task 4.1.5c: a terminal failure no longer tears the
        // notification down outright (this test's pre-Epic-4.1 name/assertion) — it rebuilds it as
        // dismissible ("tap to retry" content, setOngoing(false)) instead, per plan.md Story 4.1.5's
        // AC ("converts to a normal (dismissible) notification on terminal failure"). Outright
        // teardown is now reserved for success/cancellation (GitCloneWorkerNotificationTest.kt
        // covers the dismissible-conversion behavior itself in more detail).
        val foregroundUpdater = RecordingForegroundUpdater()
        val repo = cloningStub { _, _, _, _ ->
            DomainError.GitError.RetryExhausted(5, DomainError.GitError.FetchFailed("net down")).left()
        }
        val worker = buildWorker(repo, foregroundUpdater = foregroundUpdater)

        worker.doWork()

        val notificationManager = androidx.core.app.NotificationManagerCompat.from(context)
        val statusBarNotification = notificationManager.activeNotifications.firstOrNull { it.id == GitCloneWorker.FAILURE_NOTIFICATION_ID }
        assertTrue(statusBarNotification != null, "a dismissible tap-to-retry notification must still be visible on failure/exhaustion")
        assertTrue(
            statusBarNotification.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT == 0,
            "the notification must no longer be ongoing/non-dismissible once it's a terminal failure",
        )
    }
}

private fun immediateVoidFuture(): ListenableFuture<Void> = object : ListenableFuture<Void> {
    override fun addListener(listener: Runnable, executor: Executor) {
        executor.execute(listener)
    }
    override fun cancel(mayInterruptIfRunning: Boolean) = false
    override fun isCancelled() = false
    override fun isDone() = true
    override fun get(): Void? = null
    override fun get(timeout: Long, unit: TimeUnit): Void? = null
}
