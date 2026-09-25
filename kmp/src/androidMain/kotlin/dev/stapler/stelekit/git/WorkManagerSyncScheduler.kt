// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import arrow.core.Either
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Android implementation of [BackgroundSyncScheduler] using WorkManager.
 *
 * Schedules [GitSyncWorker] with a [NetworkType.CONNECTED] constraint.
 * Android enforces a minimum 15-minute repeat interval for periodic work.
 *
 * For sub-15-minute polling when the app is foregrounded, [GitSyncService] manages
 * its own coroutine timer via [GitSyncService.startPeriodicSync].
 */
class WorkManagerSyncScheduler(
    private val context: Context,
    private val graphId: String,
) : BackgroundSyncScheduler {

    private val workName get() = workNameFor(graphId)

    override fun schedule(intervalMinutes: Int) {
        val repeatInterval = maxOf(intervalMinutes.toLong(), MIN_INTERVAL_MINUTES)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<GitSyncWorker>(repeatInterval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(
                androidx.work.Data.Builder()
                    .putString(GitSyncWorker.KEY_GRAPH_ID, graphId)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            workName,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(workName)
    }

    companion object {
        /** Android's floor for periodic work — matches [schedule]'s existing clamp. */
        private const val MIN_INTERVAL_MINUTES = 15L

        private fun workNameFor(graphId: String) = "stelekit_git_sync_$graphId"

        /**
         * Pauses [graphId]'s scheduled periodic sync job ahead of a relocate/link's copy step
         * (Story 3.2.1), so a concurrent WorkManager fetch never races the foreground copy of
         * `.git`. Cancels the enqueued unique periodic work outright rather than merely skipping
         * one run — [resumeFor] re-enqueues it once the move completes.
         *
         * A `Context`-scoped companion function rather than an instance method: the caller
         * ([AndroidGraphMoveQuiesceStrategy]) quiesces an arbitrary [graphId] from a
         * [dev.stapler.stelekit.model.StorageMoveOperation], not necessarily one it already holds
         * a per-graph [WorkManagerSyncScheduler] instance for.
         */
        fun pauseFor(context: Context, graphId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workNameFor(graphId))
        }

        /**
         * Re-schedules [graphId]'s periodic sync job after a relocate/link's [release] step
         * completes. Re-enqueues at Android's minimum interval rather than the graph's previously
         * configured interval — [GitSyncService] re-applies the graph's real configured interval
         * the next time it starts, so this is a safety net restoring background coverage, not the
         * source of truth for the interval.
         */
        fun resumeFor(context: Context, graphId: String) {
            WorkManagerSyncScheduler(context, graphId).schedule(MIN_INTERVAL_MINUTES.toInt())
        }
    }
}

/**
 * WorkManager worker that calls [GitSyncService.fetchOnly] to check for remote changes.
 *
 * Only fetches — does not merge — to minimise battery impact and avoid data loss
 * from automatic merges when the user may be editing.
 *
 * The [GitSyncService] instance is obtained from [GitSyncServiceRegistry], which must be
 * populated from the Application class before WorkManager starts any work.
 */
class GitSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_GRAPH_ID = "graph_id"
    }

    override suspend fun doWork(): Result {
        val graphId = inputData.getString(KEY_GRAPH_ID) ?: return Result.failure()

        // Story 3.1.7: a side-effecting observation only, piggybacked on this worker's existing
        // periodic schedule — never allowed to affect this worker's own Result (ADR-002 governs
        // GitCloneWorker's own retry ownership, not this unrelated watchdog check).
        runCatching { checkAndSurfaceStuckGitCloneWorker(applicationContext, graphId) }

        // Fast path: app is running and service is registered
        val service = GitSyncServiceRegistry.getService(graphId)
        if (service != null) {
            return try {
                // fetchOnly() communicates failure via Either.Left, not by throwing — the result
                // must be inspected, not discarded, or every fetch failure silently reads as
                // Result.success() regardless of what the catch block below does (Story 1.2.3).
                when (service.fetchOnly(graphId)) {
                    is Either.Left -> Result.failure()
                    is Either.Right -> Result.success()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // ADR-002: GitOperationSupport.runGitTransportOpWithRetry (Story 1.2.2) already
                // retried internally before fetchOnly() ever threw/returned Left here — a second
                // WorkManager-level Result.retry() would stack a second, uncoordinated retry
                // layer on top of the first. Result.failure() leaves the next attempt to
                // WorkManager's own 15-minute periodic schedule, not an early re-invocation.
                Result.failure()
            }
        }

        // Slow path: process was killed and WorkManager restarted it.
        // Application.onCreate() has run, so DriverFactory is initialized.
        // Perform a standalone fetch directly without a full GitSyncService.
        return try {
            CredentialStore.init(applicationContext)
            DriverFactory.setContext(applicationContext)

            val factory = DriverFactory()
            val dbUrl = factory.getDatabaseUrl(graphId)
            val driver = factory.createDriver(dbUrl)
            val db = dev.stapler.stelekit.db.SteleDatabase(driver)

            val row = db.steleDatabaseQueries.selectGitConfig(graphId).executeAsOneOrNull()
                ?: run { driver.close(); return Result.success() }

            val config = row.toGitConfig()
            // A throwaway, uninitialized PlatformFileSystem() is deliberately fine here (unlike
            // MainActivity's construction site — see buildGitRepository): fetch() only updates
            // remote-tracking refs and never touches the working tree, so no ensureFresh/shadow
            // write-back happens on this call path even though resolveForJGit's shadowWorktreeFor
            // still correctly targets the shadow directory. The user's next foreground merge()
            // call is what surfaces fetched changes into SAF (plan.md Task 5.1.2b).
            val gitRepository = AndroidGitRepository(
                context = applicationContext,
                fileSystem = PlatformFileSystem(),
            )
            // As in the fast path above: fetch() communicates failure via Either.Left, not by
            // throwing, so the result must be inspected here too (Story 1.2.3).
            val fetchResult = gitRepository.fetch(config)

            driver.close()
            when (fetchResult) {
                is Either.Left -> Result.failure()
                is Either.Right -> Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // ADR-002: gitRepository.fetch() (Story 1.2.2e) already retried internally via
            // runGitTransportOpWithRetry — see the fast path's identical comment above.
            Result.failure()
        }
    }
}

private fun dev.stapler.stelekit.db.Git_config.toGitConfig() =
    dev.stapler.stelekit.git.model.GitConfig(
        graphId = graph_id,
        repoRoot = repo_root,
        wikiSubdir = wiki_subdir.ifEmpty { null },
        remoteName = remote_name,
        remoteBranch = remote_branch,
        authType = runCatching {
            dev.stapler.stelekit.git.model.GitAuthType.valueOf(auth_type)
        }.getOrDefault(dev.stapler.stelekit.git.model.GitAuthType.NONE),
        sshKeyPath = ssh_key_path,
        sshKeyPassphraseKey = ssh_key_passphrase_key,
        httpsTokenKey = https_token_key,
        oauthTokenKey = oauth_token_key,
        pollIntervalMinutes = poll_interval_minutes.toInt(),
        autoCommit = auto_commit != 0L,
        commitMessageTemplate = commit_message_template,
        cloneDepthState = dev.stapler.stelekit.git.model.CloneDepthState.fromRaw(clone_depth_state, shallow_depth),
    )

/**
 * Simple static registry that maps graphId → [GitSyncService].
 *
 * Populated from the Application class (or equivalent host) so [GitSyncWorker] can
 * retrieve the service without a DI container.
 *
 * In a future iteration this should be replaced with Hilt injection.
 */
object GitSyncServiceRegistry {
    private val services = mutableMapOf<String, GitSyncService>()

    fun register(graphId: String, service: GitSyncService) {
        services[graphId] = service
    }

    fun unregister(graphId: String) {
        services.remove(graphId)
    }

    fun getService(graphId: String): GitSyncService? = services[graphId]
}

/**
 * git-sync-resilience Story 3.1.7: threshold past which a `RUNNING` [GitCloneWorker] [WorkInfo]
 * is presumed killed by OEM battery optimization (no exception thrown, `onStopped()` never
 * invoked — a distinct silent-kill mechanism a correctly `setForeground()`'d service does not
 * defeat, per pre-mortem.md P1 #1) rather than still legitimately transferring — comfortably past
 * [GIT_TRANSPORT_TIMEOUT_SECONDS] (300s) and the retry loop's 10-minute `maxElapsed` wall-clock
 * deadline (Story 3.1.4), both of which bound how long a legitimately-running clone can take.
 */
const val STUCK_CLONE_WATCHDOG_THRESHOLD_MINUTES = 30

/**
 * Pure watchdog check (Task 3.1.7a): true when [state] is `RUNNING` and [startTimeMs] is more
 * than [STUCK_CLONE_WATCHDOG_THRESHOLD_MINUTES] before [nowMs] — the one failure mode in this
 * plan with no thrown exception for `classifyGitFailure` to route at all. Deliberately
 * independent of WorkManager/SharedPreferences so it's directly unit-testable; see
 * [checkAndSurfaceStuckGitCloneWorker] for the real WorkManager-backed caller.
 */
fun isGitCloneWorkerStuck(state: WorkInfo.State?, startTimeMs: Long?, nowMs: Long): Boolean {
    if (state != WorkInfo.State.RUNNING || startTimeMs == null) return false
    return (nowMs - startTimeMs) > STUCK_CLONE_WATCHDOG_THRESHOLD_MINUTES * 60_000L
}

/**
 * `SharedPreferences`-backed start-time bookkeeping for [GitCloneWorker]'s stuck-clone watchdog
 * (Task 3.1.7a) — [WorkInfo] itself exposes no start-time field, so this app tracks it
 * explicitly, keyed by graphId, alongside the enqueue in [AndroidGitCloneWorkerLauncher].
 */
object GitCloneWorkTracker {
    private const val PREFS_NAME = "stelekit_git_clone_tracker"

    fun recordStart(context: Context, graphId: String, workId: java.util.UUID, startTimeMs: Long) {
        prefs(context).edit()
            .putString(workIdKey(graphId), workId.toString())
            .putLong(startTimeKey(graphId), startTimeMs)
            .apply()
    }

    fun clear(context: Context, graphId: String) {
        prefs(context).edit().remove(workIdKey(graphId)).remove(startTimeKey(graphId)).apply()
    }

    fun trackedWorkId(context: Context, graphId: String): java.util.UUID? =
        prefs(context).getString(workIdKey(graphId), null)
            ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }

    fun startTime(context: Context, graphId: String): Long? =
        prefs(context).getLong(startTimeKey(graphId), -1L).takeIf { it >= 0 }

    private fun workIdKey(graphId: String) = "${graphId}_work_id"
    private fun startTimeKey(graphId: String) = "${graphId}_start_ms"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Real WorkManager-backed caller of [isGitCloneWorkerStuck] (Task 3.1.7b) — queries the tracked
 * work id's current [WorkInfo] and posts [GitCloneWorker.STUCK_CLONE_WATCHDOG_MESSAGE] if stuck.
 * Called from [GitSyncWorker]'s existing periodic invocation; never throws (callers must guard —
 * see `GitSyncWorker.doWork()`'s `runCatching` call site), since this is a side-effecting
 * observation, not a retry decision.
 */
suspend fun checkAndSurfaceStuckGitCloneWorker(
    context: Context,
    graphId: String,
    nowMs: Long = System.currentTimeMillis(),
) {
    val workId = GitCloneWorkTracker.trackedWorkId(context, graphId) ?: return
    val info = WorkManager.getInstance(context).getWorkInfoByIdFlow(workId).first()
    if (info == null || info.state.isFinished) {
        GitCloneWorkTracker.clear(context, graphId)
        return
    }
    if (isGitCloneWorkerStuck(info.state, GitCloneWorkTracker.startTime(context, graphId), nowMs)) {
        GitCloneWorker.postStuckCloneWatchdogNotification(context)
    }
}
