// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import arrow.core.Either
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlinx.coroutines.CancellationException

/**
 * Android `CoroutineWorker` that runs a user-initiated clone as a `dataSync` foreground service
 * (git-sync-resilience Epic 3.1), so the transfer survives the app being backgrounded/screen-locked
 * instead of dying with the Step 5 composable's own `rememberCoroutineScope()`. Enqueued by
 * [AndroidGitCloneWorkerLauncher]; delegates to [AndroidGitRepository.clone], which already
 * carries retry/timeout/shallow-clone behavior from Phases 1-2 — this worker only adds the
 * foreground-survival wrapper around it.
 *
 * Auth secrets are never passed as plain WorkManager `Data` (Task 3.1.2a) — [AndroidGitCloneWorkerLauncher]
 * pre-resolves and persists them to [CredentialStore] under a [graphId]-scoped key (see
 * [httpsTokenCredentialKey]/[sshPassphraseCredentialKey]) before enqueueing, since a suspend
 * `GitAuth` provider lambda can't survive WorkManager's process-independent execution; this
 * worker resolves them back out by the same keys, mirroring how [GitSyncWorker]'s slow path
 * already resolves credentials from a persisted [dev.stapler.stelekit.git.model.GitConfig].
 *
 * The `gitRepositoryOverride` constructor parameter is a test-only seam (mirrors
 * [AndroidGitRepository]'s own constructor-injection precedent) letting
 * `GitCloneWorkerTest`/`GitCloneWorkerThrowableSafetyTest` substitute a
 * [dev.stapler.stelekit.git.testsupport.StubGitRepository] via a custom `WorkerFactory` passed to
 * `TestListenableWorkerBuilder`, without needing a real JGit/`Context` setup. `@JvmOverloads`
 * keeps the plain 2-arg `(Context, WorkerParameters)` constructor WorkManager's default
 * reflection-based `WorkerFactory` requires in production.
 */
class GitCloneWorker @JvmOverloads constructor(
    context: Context,
    params: WorkerParameters,
    private val gitRepositoryOverride: GitRepository? = null,
) : CoroutineWorker(context, params) {

    private val logger = Logger("GitCloneWorker")

    override suspend fun doWork(): Result {
        try {
            return runClone()
        } catch (e: CancellationException) {
            // Story 3.1.6 (Tasks 3.1.6a/3.1.6c): this catch MUST come before the generic
            // catch(Throwable) below — a WorkManager cancellation (Story 4.1.4's Cancel button,
            // via cancelUniqueWork) must always propagate as cancellation, never be converted to
            // Result.failure(). Plan.md's Task 3.1.6c originally sketched an onStopped() override
            // as the cancellation-path teardown owner, but CoroutineWorker.onStopped() is `final`
            // (verified by compiling against androidx.work 2.9.1) — it cannot be overridden.
            // CoroutineWorker cancels this worker's own coroutine Job internally when WorkManager
            // stops it, which is exactly what surfaces here as a CancellationException, so this
            // catch clause — not an onStopped() override — is the actual, only place every
            // WorkManager-initiated cancellation reaches. Tear the notification down here, then
            // rethrow so cancellation still propagates unconverted.
            teardownNotification()
            throw e
        } catch (e: Throwable) {
            // CLAUDE.md "Uncaught coroutine Throwables kill the process on Android": catch
            // Throwable, not just Exception, so an OutOfMemoryError (a real JGit pack-parsing
            // allocation hot spot, now running longer inside a foreground service the user can't
            // see, thanks to retry+backoff) fails the operation visibly instead of silently
            // killing the app process — mirrors SteleKitApplication.onCreate's fix for the same
            // bug class (Story 3.1.6).
            logger.error("doWork: uncaught throwable, failing worker", e)
            teardownNotification()
            return Result.failure()
        }
    }

    private suspend fun runClone(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val localPath = inputData.getString(KEY_LOCAL_PATH) ?: return Result.failure()
        val graphId = inputData.getString(KEY_GRAPH_ID) ?: return Result.failure()
        val sshKeyPath = inputData.getString(KEY_SSH_KEY_PATH)

        CredentialStore.init(applicationContext)
        val auth = resolveAuth(inputData.getString(KEY_AUTH_TYPE), graphId, sshKeyPath, CredentialStore())

        try {
            setForeground(getForegroundInfo())
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // Task 3.1.2e: OS denied foreground promotion (e.g. a race where WorkManager runs
            // doWork() just as the app leaves the foreground-eligible window). The transfer
            // itself hasn't failed — only its background-survival guarantee is absent for this
            // run — so this logs and falls through to the transfer rather than failing.
            logger.warn("doWork: setForeground() denied, continuing without foreground promotion", e)
            onForegroundDenied()
        }

        val gitRepository = gitRepositoryOverride ?: AndroidGitRepository(
            context = applicationContext,
            fileSystem = PlatformFileSystem(),
        )
        val result = gitRepository.clone(url, localPath, auth) { phase -> onCloneProgress(phase) }

        teardownNotification()
        return when (result) {
            is Either.Right -> Result.success()
            is Either.Left -> {
                logger.error("doWork: clone failed graphId=$graphId error=${result.value}")
                // ADR-002 / Story 3.1.5: runGitTransportOpWithRetry (inside
                // AndroidGitRepository.clone()) already retried internally before ever returning
                // Left here — this worker is a retry *consumer*, never a second retry owner.
                // Always Result.failure(), never Result.retry() — do not "helpfully" add it back.
                Result.failure()
            }
        }
    }

    /**
     * Placeholder until `GitTransportRetryState.onStateChange` lands (Epic 4.1, Task 4.1.2b — see
     * plan.md's own "wire a placeholder no-op callback now" guidance for this exact case). Once
     * that callback exists, this should emit `GitTransportRetryState.Attempting(progress,
     * foregroundPromoted = false)` through it so Step 5 doesn't imply a survivability guarantee
     * that isn't in effect for this run.
     */
    private fun onForegroundDenied() {}

    private fun onCloneProgress(phase: String) {
        setProgressAsync(workDataOf(KEY_PROGRESS_PHASE to phase))
        postProgressNotification(phase)
    }

    private fun postProgressNotification(phase: String) {
        ensureNotificationChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(IN_PROGRESS_TITLE)
            .setContentText(phase)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notifySafely(applicationContext, NOTIFICATION_ID, notification)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureNotificationChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(IN_PROGRESS_TITLE)
            .setContentText("Starting…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /**
     * The one shared teardown call every terminal path reuses (success, failure/RetryExhausted,
     * uncaught Throwable, cancellation) — cancelling outright satisfies "not lingering as
     * in-progress" for now; a richer dismissible "tap to retry" notification is Task 4.1.5c's job.
     */
    private fun teardownNotification() {
        try {
            NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID)
        } catch (e: SecurityException) {
            logger.warn("teardownNotification: cancel denied", e)
        }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_LOCAL_PATH = "local_path"
        const val KEY_GRAPH_ID = "graph_id"
        const val KEY_AUTH_TYPE = "auth_type"
        const val KEY_SSH_KEY_PATH = "ssh_key_path"
        const val KEY_PROGRESS_PHASE = "progress_phase"

        const val AUTH_HTTPS_TOKEN = "HTTPS_TOKEN"
        const val AUTH_SSH_KEY = "SSH_KEY"
        const val AUTH_NONE = "NONE"

        internal const val NOTIFICATION_CHANNEL_ID = "stelekit_git_sync"
        internal const val NOTIFICATION_ID = 90020
        internal const val WATCHDOG_NOTIFICATION_ID = 90021
        private const val IN_PROGRESS_TITLE = "Syncing graph"

        /** Story 3.1.7's exact user-facing copy for the stuck-clone battery-optimization watchdog. */
        const val STUCK_CLONE_WATCHDOG_MESSAGE =
            "Sync may have been stopped by battery optimization — check your device's battery settings for SteleKit."

        /** [CredentialStore] key [AndroidGitCloneWorkerLauncher] persists a resolved HTTPS
         * token/OAuth token under before enqueueing, scoped by [graphId] and distinct from the
         * long-lived `git_https_token_$graphId`/`git_github_oauth_$graphId` keys
         * [dev.stapler.stelekit.ui.screens.git.GitSetupScreenSaveLogic] uses for a *saved*
         * [dev.stapler.stelekit.git.model.GitConfig], so an in-flight clone's transient credential
         * never collides with (or is overwritten by) the persisted-config credential lifecycle. */
        fun httpsTokenCredentialKey(graphId: String) = "git_clone_https_token_$graphId"

        /** [CredentialStore] key for a resolved SSH key passphrase — see [httpsTokenCredentialKey]. */
        fun sshPassphraseCredentialKey(graphId: String) = "git_clone_ssh_passphrase_$graphId"

        private fun resolveAuth(
            authType: String?,
            graphId: String,
            sshKeyPath: String?,
            credentialAccess: CredentialAccess,
        ): GitAuth = when (authType) {
            AUTH_HTTPS_TOKEN -> GitAuth.HttpsToken(
                username = "",
                tokenProvider = { credentialAccess.retrieve(httpsTokenCredentialKey(graphId)) },
            )
            AUTH_SSH_KEY -> GitAuth.SshKey(
                keyPath = sshKeyPath ?: "",
                passphraseProvider = { credentialAccess.retrieve(sshPassphraseCredentialKey(graphId)) },
            )
            else -> GitAuth.None
        }

        private fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(NOTIFICATION_CHANNEL_ID, "Git sync", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        private fun notifySafely(context: Context, id: Int, notification: android.app.Notification) {
            try {
                NotificationManagerCompat.from(context).notify(id, notification)
            } catch (e: SecurityException) {
                // POST_NOTIFICATIONS not granted — best effort, must not fail the clone.
                Logger("GitCloneWorker").warn("notifySafely: denied", e)
            }
        }

        /**
         * Story 3.1.7's watchdog surface: posts a dismissible notification with
         * [STUCK_CLONE_WATCHDOG_MESSAGE], reusing this worker's own notification-channel plumbing
         * rather than inventing a second one. Called from [checkAndSurfaceStuckGitCloneWorker]
         * (`WorkManagerSyncScheduler.kt`), driven by [GitSyncWorker]'s existing periodic run.
         */
        fun postStuckCloneWatchdogNotification(context: Context) {
            ensureNotificationChannel(context)
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Sync interrupted")
                .setContentText(STUCK_CLONE_WATCHDOG_MESSAGE)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            notifySafely(context, WATCHDOG_NOTIFICATION_ID, notification)
        }
    }
}
