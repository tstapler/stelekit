// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
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
import arrow.core.right
import com.google.common.util.concurrent.ListenableFuture
import dev.stapler.stelekit.git.testsupport.cloningStub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Story 3.1.6's Throwable-safety boundary for [GitCloneWorker.doWork]: an `Error` subtype (e.g.
 * `OutOfMemoryError`, a real JGit pack-parsing allocation hot spot) must be caught and converted
 * to `Result.failure()` with the foreground notification torn down, never left to propagate
 * uncaught and kill the Android process — while a `CancellationException` (a WorkManager-driven
 * cancellation, e.g. Story 4.1.4's Cancel button) must always propagate unconverted. See
 * [GitCloneWorker.doWork]'s kdoc comment for why the ordering between these two catch clauses is
 * mandatory and why there is no `onStopped()` override (`CoroutineWorker.onStopped()` is `final`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GitCloneWorkerThrowableSafetyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class RecordingForegroundUpdater : ForegroundUpdater {
        var lastForegroundInfo: ForegroundInfo? = null
            private set

        override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> {
            lastForegroundInfo = foregroundInfo
            return immediateVoidFuture()
        }
    }

    private class NoOpProgressUpdater : ProgressUpdater {
        override fun updateProgress(context: Context, id: UUID, data: Data): ListenableFuture<Void> = immediateVoidFuture()
    }

    private fun defaultInputData(): Data = workDataOf(
        GitCloneWorker.KEY_URL to "https://example.invalid/graph.git",
        GitCloneWorker.KEY_LOCAL_PATH to "/tmp/graph",
        GitCloneWorker.KEY_GRAPH_ID to "graph-1",
        GitCloneWorker.KEY_AUTH_TYPE to GitCloneWorker.AUTH_NONE,
    )

    private fun buildWorker(gitRepository: GitRepository, foregroundUpdater: ForegroundUpdater = RecordingForegroundUpdater()): GitCloneWorker =
        TestListenableWorkerBuilder<GitCloneWorker>(context)
            .setInputData(defaultInputData())
            .setForegroundUpdater(foregroundUpdater)
            .setProgressUpdater(NoOpProgressUpdater())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = GitCloneWorker(appContext, workerParameters, gitRepository)
            })
            .build()

    @Test
    fun `doWork() returns Result failure and tears down the foreground notification when the wrapped clone delegate throws OutOfMemoryError`() = runBlocking {
        val repo = cloningStub { _, _, _, _ -> throw OutOfMemoryError("simulated JGit pack-parsing OOM") }
        val worker = buildWorker(repo)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        val notificationManager = androidx.core.app.NotificationManagerCompat.from(context)
        assertTrue(
            notificationManager.activeNotifications.none { it.id == GitCloneWorker.NOTIFICATION_ID },
            "the foreground notification must be torn down when an Error propagates",
        )
    }

    @Test
    fun `doWork() rethrows CancellationException rather than converting it to Result failure when the wrapped clone delegate is cancelled`() = runBlocking {
        val repo = cloningStub { _, _, _, _ -> throw CancellationException("worker stopped") }
        val worker = buildWorker(repo)

        assertFailsWith<CancellationException> { worker.doWork() }
        Unit
    }

    @Test
    fun `doWork() tears down the foreground notification on the CancellationException path too`() = runBlocking {
        val foregroundUpdater = RecordingForegroundUpdater()
        val repo = cloningStub { _, _, _, _ -> throw CancellationException("worker stopped") }
        val worker = buildWorker(repo, foregroundUpdater)

        runCatching { worker.doWork() }

        val notificationManager = androidx.core.app.NotificationManagerCompat.from(context)
        assertTrue(
            notificationManager.activeNotifications.none { it.id == GitCloneWorker.NOTIFICATION_ID },
            "cancellation must still tear down the foreground notification — CoroutineWorker.onStopped() " +
                "is final and cannot own this, so doWork()'s catch(CancellationException) clause must",
        )
    }

    @Test
    fun `a successful clone never throws and returns Result success`() = runBlocking {
        val repo = cloningStub { _, _, _, _ -> Unit.right() }
        val worker = buildWorker(repo)

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
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
