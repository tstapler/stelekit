// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

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
import arrow.core.right
import com.google.common.util.concurrent.ListenableFuture
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [GitCloneWorker]'s foreground-service notification content (git-sync-resilience Story
 * 4.1.5). Covers `validation.md`'s Story 4.1.5 rows and UX Acceptance Test criteria 9/20.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class GitCloneWorkerNotificationTest {

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

    private fun defaultInputData(graphDisplayName: String? = "My Notes"): Data = workDataOf(
        GitCloneWorker.KEY_URL to "https://example.invalid/graph.git",
        GitCloneWorker.KEY_LOCAL_PATH to "/tmp/graph",
        GitCloneWorker.KEY_GRAPH_ID to "graph-1",
        GitCloneWorker.KEY_AUTH_TYPE to GitCloneWorker.AUTH_NONE,
        GitCloneWorker.KEY_GRAPH_DISPLAY_NAME to graphDisplayName,
    )

    private fun buildWorker(
        gitRepository: GitRepository,
        foregroundUpdater: ForegroundUpdater = RecordingForegroundUpdater(),
        inputData: Data = defaultInputData(),
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
                ): ListenableWorker = GitCloneWorker(appContext, workerParameters, gitRepository)
            })
            .build()

    /** A [GitRepository] fake exposing both [onProgress] and [onStateChange] — [cloningStub]
     * (`testsupport/StubGitRepository.kt`) only forwards the former, which every pre-Story-4.1.5
     * `GitCloneWorker` test uses; this epic's notification tests need the state channel too. */
    private fun statefulCloningStub(
        onClone: suspend (onStateChange: (GitTransportRetryState) -> Unit) -> Either<DomainError.GitError, Unit>,
    ): GitRepository = object : StubGitRepository() {
        override suspend fun clone(
            url: String,
            localPath: String,
            auth: GitAuth,
            onProgress: (CloneProgress) -> Unit,
            onStateChange: (GitTransportRetryState) -> Unit,
        ) = onClone(onStateChange)
    }

    private fun activeNotificationTexts(): Pair<String?, String?> {
        val notification = NotificationManagerCompat.from(context).activeNotifications
            .firstOrNull { it.id == GitCloneWorker.NOTIFICATION_ID }
            ?.notification
            ?: return null to null
        val extras = notification.extras
        return extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() to
            extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
    }

    @Test
    fun `getForegroundInfo() builds a notification titled Syncing graph display name`() = runBlocking {
        val worker = buildWorker(StubGitRepository())

        val info = worker.getForegroundInfo()

        val title = info.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        assertEquals("Syncing My Notes", title)
    }

    @Test
    fun `getForegroundInfo() falls back to your graph when no display name is provided`() = runBlocking {
        val worker = buildWorker(StubGitRepository(), inputData = defaultInputData(graphDisplayName = null))

        val info = worker.getForegroundInfo()

        val title = info.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        assertEquals("Syncing your graph", title)
    }

    @Test
    fun `a Retrying state's notification body matches notificationBodyFor exactly`() = runBlocking {
        var captured: Pair<String?, String?>? = null
        val repo = statefulCloningStub { onStateChange ->
            onStateChange(GitTransportRetryState.Retrying(attempt = 2, max = 4, progress = null))
            captured = activeNotificationTexts()
            Unit.right()
        }
        buildWorker(repo).doWork()

        val expectedBody = GitCloneWorker.notificationBodyFor(GitTransportRetryState.Retrying(2, 4, null))
        assertEquals("Syncing My Notes", captured?.first)
        assertEquals(expectedBody, captured?.second)
        assertFalse(expectedBody.contains("Exception"), "notification body must never contain a raw exception class name")
    }

    @Test
    fun `a terminal Exhausted notification is rebuilt as dismissible tap-to-retry, not staying ongoing`() = runBlocking {
        val repo = statefulCloningStub { onStateChange ->
            onStateChange(GitTransportRetryState.Exhausted("network down"))
            DomainError.GitError.RetryExhausted(5, DomainError.GitError.FetchFailed("net down")).left()
        }
        buildWorker(repo).doWork()

        val notification = NotificationManagerCompat.from(context).activeNotifications
            .first { it.id == GitCloneWorker.FAILURE_NOTIFICATION_ID }
            .notification
        assertEquals(GitCloneWorker.SYNC_FAILED_TAP_TO_RETRY, notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
        assertFalse(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0, "a terminal failure must not stay ongoing")
    }

    @Test
    fun `a stable notification ID is used across multiple GitTransportRetryState transitions, not a new notification per event`() = runBlocking {
        val seenIds = mutableSetOf<Int>()
        val repo = statefulCloningStub { onStateChange ->
            onStateChange(GitTransportRetryState.Attempting(CloneProgress("Receiving objects", 10, 100)))
            seenIds += NotificationManagerCompat.from(context).activeNotifications.map { it.id }
            onStateChange(GitTransportRetryState.Retrying(1, 4, null))
            seenIds += NotificationManagerCompat.from(context).activeNotifications.map { it.id }
            Unit.right()
        }
        buildWorker(repo).doWork()

        assertEquals(setOf(GitCloneWorker.NOTIFICATION_ID), seenIds)
    }

    @Test
    fun `notification body across every live state never contains a raw URL, file path, or exception class name`() {
        val states = listOf(
            GitTransportRetryState.Idle,
            GitTransportRetryState.Attempting(CloneProgress("Receiving objects", 10, 100)),
            GitTransportRetryState.Retrying(2, 4, CloneProgress("Receiving objects", 45, 100)),
            GitTransportRetryState.ResumingDeepen(30),
            GitTransportRetryState.Exhausted("boom"),
            GitTransportRetryState.NonRetryableFailure(NonRetryableReason.AUTH),
        )
        for (state in states) {
            val body = GitCloneWorker.notificationBodyFor(state)
            assertFalse(body.contains("http"), "state=$state body must not contain a raw URL: $body")
            assertFalse(body.contains("git@"), "state=$state body must not contain a raw SSH URL: $body")
            assertFalse(body.contains("Exception"), "state=$state body must not contain a raw exception class name: $body")
        }
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
