// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
 * foreground-survival wrapper around it, and (Epic 4.1) drives its live notification content from
 * the same [GitTransportRetryState] stream Step 5 renders from.
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
    private val credentialAccessOverride: CredentialAccess? = null,
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
        } finally {
            // Every terminal path (success, failure, cancellation, Throwable) drops the transient
            // PAT/passphrase the launcher persisted; only process death mid-run leaves it for the
            // WorkManager-restarted run, which is why this isn't done at the start.
            inputData.getString(KEY_GRAPH_ID)?.let {
                clearTransientCredentials(applicationContext, it, credentialAccessOverride)
            }
        }
    }

    private suspend fun runClone(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val localPath = inputData.getString(KEY_LOCAL_PATH) ?: return Result.failure()
        val graphId = inputData.getString(KEY_GRAPH_ID) ?: return Result.failure()
        val sshKeyPath = inputData.getString(KEY_SSH_KEY_PATH)
        val graphDisplayName = inputData.getString(KEY_GRAPH_DISPLAY_NAME)

        // A stale failure notification from a previous run for this graph must not linger.
        cancelFailureNotification()

        val credentialAccess = credentialAccessOverride ?: run {
            CredentialStore.init(applicationContext)
            CredentialStore()
        }
        val auth = resolveAuth(inputData.getString(KEY_AUTH_TYPE), graphId, sshKeyPath, credentialAccess)

        var foregroundPromoted = true
        try {
            setForeground(getForegroundInfo())
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // Task 3.1.2e: OS denied foreground promotion (e.g. a race where WorkManager runs
            // doWork() just as the app leaves the foreground-eligible window). The transfer
            // itself hasn't failed — only its background-survival guarantee is absent for this
            // run — so this logs and falls through to the transfer rather than failing.
            logger.warn("doWork: setForeground() denied, continuing without foreground promotion", e)
            foregroundPromoted = false
            onForegroundDenied(graphId, graphDisplayName)
        }

        val gitRepository = gitRepositoryOverride ?: AndroidGitRepository(
            context = applicationContext,
            fileSystem = PlatformFileSystem(),
        )
        val throttle = ProgressThrottle()
        var terminalReasonTag: String? = null
        val result = gitRepository.clone(
            url,
            localPath,
            auth,
            onProgress = { progress ->
                if (throttle.shouldEmit(progress)) {
                    onCloneProgress(progress, graphId, graphDisplayName, foregroundPromoted)
                }
            },
            onStateChange = { state ->
                if (state is GitTransportRetryState.NonRetryableFailure) terminalReasonTag = state.reason
                onRetryStateChange(state, graphId, graphDisplayName, foregroundPromoted)
            },
        )

        return when (result) {
            is Either.Right -> {
                teardownNotification()
                Result.success()
            }
            is Either.Left -> {
                logger.error("doWork: clone failed graphId=$graphId error=${result.value}")
                // Task 4.1.5c: a terminal failure converts the notification to a dismissible
                // "tap to retry" one instead of tearing it down outright — success/cancellation
                // are the two paths that still dismiss it entirely.
                teardownNotification()
                postTerminalFailureNotification(graphId, graphDisplayName)
                // ADR-002 / Story 3.1.5: runGitTransportOpWithRetry (inside
                // AndroidGitRepository.clone()) already retried internally before ever returning
                // Left here — this worker is a retry *consumer*, never a second retry owner.
                // Always Result.failure(), never Result.retry() — do not "helpfully" add it back.
                // The payload carries the terminal error kind so the launcher can rebuild the
                // DomainError and terminal GitTransportRetryState for Step 5.
                Result.failure(GitCloneWorkerData.encodeFailure(result.value, terminalReasonTag))
            }
        }
    }

    /**
     * Task 4.1.2b/3.1.2e: `setForeground()` was denied for this run — surface
     * `Attempting(progress, foregroundPromoted = false)` so Step 5/the notification never imply a
     * background-survival guarantee that isn't actually in effect (the transfer itself still
     * proceeds; only this signal changes).
     */
    private fun onForegroundDenied(graphId: String, graphDisplayName: String?) {
        onRetryStateChange(
            GitTransportRetryState.Attempting(CloneProgress("", 0, 0), foregroundPromoted = false),
            graphId,
            graphDisplayName,
            foregroundPromoted = false,
        )
    }

    private fun onCloneProgress(progress: CloneProgress, graphId: String, graphDisplayName: String?, foregroundPromoted: Boolean) {
        setProgressAsync(GitCloneWorkerData.encodeState(GitTransportRetryState.Attempting(progress, foregroundPromoted)))
        // Task 4.1.5b: an indeterminate bar (no percent known yet) until totalWork is meaningfully
        // known — postStateNotification/notificationProgressFor already encode that rule from the
        // same CloneProgress this callback carries, so this just re-renders the Attempting state
        // with fresh numbers on every JGit tick (setOnlyAlertOnce(true) keeps this silent).
        postStateNotification(
            GitTransportRetryState.Attempting(progress, foregroundPromoted),
            graphId,
            graphDisplayName,
        )
    }

    private fun onRetryStateChange(state: GitTransportRetryState, graphId: String, graphDisplayName: String?, foregroundPromoted: Boolean) {
        val effectiveState = if (state is GitTransportRetryState.Attempting && !foregroundPromoted) {
            state.copy(foregroundPromoted = false)
        } else {
            state
        }
        // Published for the launcher (and through it Step 5) — same stream the notification renders.
        setProgressAsync(GitCloneWorkerData.encodeState(effectiveState))
        when (effectiveState) {
            is GitTransportRetryState.Exhausted, is GitTransportRetryState.NonRetryableFailure ->
                postTerminalFailureNotification(graphId, graphDisplayName)
            else -> postStateNotification(effectiveState, graphId, graphDisplayName)
        }
    }

    /** Live, non-dismissible ("in progress") notification content for [state] — Task 4.1.5a/b. */
    private fun postStateNotification(state: GitTransportRetryState, graphId: String, graphDisplayName: String?) {
        ensureNotificationChannel(applicationContext)
        val notification = baseNotificationBuilder(applicationContext, graphId, graphDisplayName)
            .setContentText(notificationBodyFor(state))
            .setOngoing(true)
            .setAutoCancel(false)
            .apply { applyProgressBar(this, state) }
            .build()
        notifySafely(applicationContext, NOTIFICATION_ID, notification)
    }

    /**
     * Task 4.1.5c: converts the live notification into a dismissible "tap to retry" one on a
     * terminal failure (`Exhausted`/`NonRetryableFailure`) — `setOngoing(false)` +
     * `setAutoCancel(true)`, distinct from [teardownNotification]'s outright cancel on
     * success/manual-cancel.
     */
    private fun postTerminalFailureNotification(graphId: String, graphDisplayName: String?) {
        ensureNotificationChannel(applicationContext)
        val notification = baseNotificationBuilder(applicationContext, graphId, graphDisplayName)
            .setContentText(SYNC_FAILED_TAP_TO_RETRY)
            .setOngoing(false)
            .setAutoCancel(true)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .build()
        // Separate id: the foreground notification (NOTIFICATION_ID) is owned by WorkManager's
        // foreground service and is removed when the worker finishes, which would take a failure
        // notification sharing its id with it.
        notifySafely(applicationContext, FAILURE_NOTIFICATION_ID, notification)
    }

    private fun cancelFailureNotification() {
        try {
            NotificationManagerCompat.from(applicationContext).cancel(FAILURE_NOTIFICATION_ID)
        } catch (e: SecurityException) {
            logger.warn("cancelFailureNotification: cancel denied", e)
        }
    }

    private fun applyProgressBar(builder: NotificationCompat.Builder, state: GitTransportRetryState): NotificationCompat.Builder {
        val progress = notificationProgressPercent(state)
        return if (progress != null) {
            builder.setProgress(100, progress, false)
        } else {
            // Task 4.1.5b: indeterminate until a real percentage is known — never fake one.
            builder.setProgress(0, 0, true)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureNotificationChannel(applicationContext)
        val graphDisplayName = inputData.getString(KEY_GRAPH_DISPLAY_NAME)
        val graphId = inputData.getString(KEY_GRAPH_ID)
        val notification = baseNotificationBuilder(applicationContext, graphId, graphDisplayName)
            .setContentText("Starting…")
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /**
     * The one shared teardown call every terminal path reuses (success, uncaught Throwable,
     * cancellation) — cancels the notification outright. A terminal *failure* uses
     * [postTerminalFailureNotification] instead (Task 4.1.5c) — it stays visible as
     * dismissible/"tap to retry" rather than disappearing silently.
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
        const val KEY_GRAPH_DISPLAY_NAME = "graph_display_name"
        const val KEY_AUTH_TYPE = "auth_type"
        const val KEY_SSH_KEY_PATH = "ssh_key_path"
        const val KEY_PROGRESS_PHASE = "progress_phase"

        const val AUTH_HTTPS_TOKEN = "HTTPS_TOKEN"
        const val AUTH_SSH_KEY = "SSH_KEY"
        const val AUTH_NONE = "NONE"

        internal const val NOTIFICATION_CHANNEL_ID = "stelekit_git_sync"
        internal const val NOTIFICATION_ID = 90020
        internal const val WATCHDOG_NOTIFICATION_ID = 90021
        internal const val FAILURE_NOTIFICATION_ID = 90022

        /** Task 4.1.5c's exact "terminal failure" notification body — Exhausted and
         * NonRetryableFailure share it; Step 5 (a richer surface) distinguishes the two with
         * separate copy/styling (Story 4.1.3), but the notification's one-line body doesn't. */
        const val SYNC_FAILED_TAP_TO_RETRY = "Sync failed — tap to retry"

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

        /** Removes the transient clone credentials persisted for [graphId]; never throws. */
        internal fun clearTransientCredentials(
            context: Context,
            graphId: String,
            credentialAccess: CredentialAccess? = null,
        ) {
            try {
                val store = credentialAccess ?: run {
                    CredentialStore.init(context)
                    CredentialStore()
                }
                store.delete(httpsTokenCredentialKey(graphId))
                store.delete(sshPassphraseCredentialKey(graphId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger("GitCloneWorker").warn("clearTransientCredentials: failed", e)
            }
        }

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

        /**
         * Task 4.1.5a: title/deep-link content shared by every notification this worker posts —
         * "Syncing {graph display name}" (never a raw URL/path, per `design/ux.md`'s jargon-
         * avoidance rule), tapping deep-links toward Step 5. The `PendingIntent` targets the app's
         * launcher activity with [EXTRA_OPEN_GIT_SETUP_GRAPH_ID] as an intent extra, which
         * `MainActivity` reads and routes to Git Setup Step 5 via
         * `StelekitViewModel.openGitSetupForRetry`.
         */
        private fun baseNotificationBuilder(
            context: Context,
            graphId: String?,
            graphDisplayName: String?,
        ): NotificationCompat.Builder {
            val title = "Syncing ${graphDisplayName?.takeIf { it.isNotBlank() } ?: "your graph"}"
            return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(deepLinkPendingIntent(context, graphId))
        }

        private fun deepLinkPendingIntent(context: Context, graphId: String?): PendingIntent? {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: return null
            launchIntent.putExtra(EXTRA_OPEN_GIT_SETUP_GRAPH_ID, graphId)
            launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getActivity(context, NOTIFICATION_ID, launchIntent, flags)
        }

        /** Intent extra `MainActivity` reads to deep-link straight to Step 5 for this graph. */
        const val EXTRA_OPEN_GIT_SETUP_GRAPH_ID = "dev.stapler.stelekit.OPEN_GIT_SETUP_GRAPH_ID"

        /** Body text for a live (non-terminal) [state] — Task 4.1.5a. Mirrors the *meaning* of
         * Step 5's own [GitTransportRetryState] copy (`GitSetupStep5TestAndSave.kt`) so the two
         * surfaces never disagree about what's happening, even though each surface's exact wording
         * follows its own established convention (`design/ux.md`'s Surface A/D wireframes) — Step
         * 5 splits primary/secondary text, this is one line for a system notification. */
        internal fun notificationBodyFor(state: GitTransportRetryState): String = when (state) {
            is GitTransportRetryState.Idle -> "Starting…"
            is GitTransportRetryState.Attempting -> percentOf(state.progress)
                ?.let { "Cloning — $it%" } ?: "Cloning…"
            is GitTransportRetryState.Retrying -> {
                val base = "Reconnecting… (attempt ${state.attempt} of ${state.max})"
                state.progress?.let { percentOf(it) }?.let { "$base — $it%" } ?: base
            }
            is GitTransportRetryState.ResumingDeepen -> state.percent?.let { "Resuming — $it%" } ?: "Resuming…"
            is GitTransportRetryState.Exhausted, is GitTransportRetryState.NonRetryableFailure ->
                SYNC_FAILED_TAP_TO_RETRY
        }

        private fun notificationProgressPercent(state: GitTransportRetryState): Int? = when (state) {
            is GitTransportRetryState.Attempting -> percentOf(state.progress)
            is GitTransportRetryState.Retrying -> state.progress?.let { percentOf(it) }
            is GitTransportRetryState.ResumingDeepen -> state.percent
            else -> null
        }

        private fun percentOf(progress: CloneProgress): Int? =
            if (progress.totalWork > 0) (progress.completed * 100 / progress.totalWork) else null

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
