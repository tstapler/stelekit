// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationManagerCompat
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
import arrow.core.Either
import arrow.core.left
import com.google.common.util.concurrent.ListenableFuture
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.testsupport.FailureSequence
import dev.stapler.stelekit.git.testsupport.FailureSequenceGitRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * git-sync-resilience Story 6.1.3 (Epic 6.1): an end-to-end regression proving the sharpest common
 * failure case — a bad-credentials clone — fails within exactly one [GitCloneWorker.doWork()]
 * attempt and releases the foreground service immediately, not just at the raw
 * `runGitTransportOpWithRetry` unit level (`GitTransportRetryTest.kt`'s
 * `returns Left after exactly 1 attempt when the failure classifies Permanent` already covers
 * that). `GitCloneWorkerTest.kt` already has a same-shaped `cloningStub`-based test; this one is
 * additionally driven through [FailureSequenceGitRepository] so `invocationCount` gives an explicit,
 * unambiguous count of how many times [GitRepository.clone] was actually invoked (rather than
 * inferring "once" from the absence of a loop), and asserts wall-clock timing directly — proving no
 * backoff delay was introduced by the worker itself. `GitCloneWorker` is a retry *consumer*
 * (ADR-002) — [FailureSequenceGitRepository.clone] itself doesn't loop internally (see
 * `StubGitRepository.kt`), so this test is really proving `GitCloneWorker`'s own single-call-and-
 * report behavior, complementing Story 6.1.2's proof that the retry *loop* itself works correctly
 * against a real JGit exception.
 */
@RunWith(RobolectricTestRunner::class)
class GitCloneWorkerFastFailIntegrationTest {

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

    private fun buildWorker(gitRepository: GitRepository): GitCloneWorker =
        TestListenableWorkerBuilder<GitCloneWorker>(context)
            .setInputData(
                workDataOf(
                    GitCloneWorker.KEY_URL to "https://example.invalid/graph.git",
                    GitCloneWorker.KEY_LOCAL_PATH to "/tmp/graph",
                    GitCloneWorker.KEY_GRAPH_ID to "graph-1",
                    GitCloneWorker.KEY_AUTH_TYPE to GitCloneWorker.AUTH_NONE,
                ),
            )
            .setForegroundUpdater(RecordingForegroundUpdater())
            .setProgressUpdater(NoOpProgressUpdater())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = GitCloneWorker(appContext, workerParameters, gitRepository)
            })
            .build()

    // Story 6.1.3's full acceptance criterion (validation.md): "GitCloneWorker.doWork() against a
    // StubGitRepository configured to always throw an auth-shaped failure returns Result.failure()
    // after exactly one attempt, and the foreground notification is dismissed/converted to failure
    // state immediately, not after any backoff delay." Kept short here (not verbatim) because the
    // JVM synthesizes this method's lambda class names from its own name — the full sentence blew
    // the filesystem's ~255-byte filename limit and failed the build with a misleading
    // "Permission denied" on the generated .class file, not a length-related error.
    @Test
    fun `doWork() against an auth-always-failing FailureSequenceGitRepository fails after exactly one attempt with no backoff delay`() =
        runBlocking {
            // "Always fails" is modeled via FailureSequence's onSuccess (not its `failures` list,
            // which is for a bounded "fail N times then succeed" sequence) returning a Left on
            // every invocation — see FailureSequence.kt's own kdoc.
            val cloneSequence = FailureSequence<Either<DomainError.GitError, Unit>>(
                failures = emptyList(),
                onSuccess = { DomainError.GitError.AuthFailed("bad credentials").left() },
            )
            val repo = FailureSequenceGitRepository(cloneSequence = cloneSequence)
            val worker = buildWorker(repo)

            val startMs = System.currentTimeMillis()
            val result = worker.doWork()
            val elapsedMs = System.currentTimeMillis() - startMs

            assertEquals(ListenableWorker.Result.failure(), result)
            assertEquals(
                1,
                cloneSequence.invocationCount,
                "GitCloneWorker must call clone() exactly once — it is a retry consumer (ADR-002), never a second retry owner",
            )
            assertTrue(
                elapsedMs < 2_000,
                "doWork() must fail fast with no backoff delay of its own, took ${elapsedMs}ms",
            )

            val notificationManager = NotificationManagerCompat.from(context)
            val statusBarNotification = notificationManager.activeNotifications
                .firstOrNull { it.id == GitCloneWorker.NOTIFICATION_ID }
            assertTrue(
                statusBarNotification != null,
                "the foreground notification must be converted to a dismissible failure-state notification immediately, not torn down silently",
            )
            assertTrue(
                statusBarNotification.notification.flags and Notification.FLAG_ONGOING_EVENT == 0,
                "the notification must no longer be ongoing/non-dismissible once it's a terminal failure",
            )
        }
}

// File-scope (not a class member) so GitCloneWorkerFastFailIntegrationTest's nested (non-inner)
// ForegroundUpdater/ProgressUpdater fakes can call it without an outer-instance reference — mirrors
// GitCloneWorkerTest.kt's identical helper.
private fun immediateVoidFuture(): ListenableFuture<Void> = object : ListenableFuture<Void> {
    override fun addListener(listener: Runnable, executor: Executor) = executor.execute(listener)
    override fun cancel(mayInterruptIfRunning: Boolean) = false
    override fun isCancelled() = false
    override fun isDone() = true
    override fun get(): Void? = null
    override fun get(timeout: Long, unit: TimeUnit): Void? = null
}
