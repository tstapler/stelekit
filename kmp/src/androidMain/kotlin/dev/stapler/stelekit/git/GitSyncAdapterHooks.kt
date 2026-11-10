// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.work.Data
import dev.stapler.stelekit.jobs.schedule.TaskAdapterHooks
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.platform.PlatformSettings
import kotlinx.coroutines.CancellationException

/**
 * The androidMain behaviour `GitSyncWorker.doWork` carries today, as hook functions: the clone-worker
 * watchdog and the slow-path yield (preRun), and the `SyncNotifier` tick (postRun). The thin
 * `GitSyncWorker` (Story 3.1.2) calls these same functions in the same order; they wrap the existing
 * [checkAndSurfaceStuckGitCloneWorker] / [isGitCloneWorkerRunningFor], so their tests stay valid.
 */
object GitSyncAdapterHooks : TaskAdapterHooks {
    override suspend fun preRun(context: Context, key: TaskKey, input: Data): String? {
        val graphId = key.scope.value
        runWatchdog(context, graphId)
        return if (shouldYieldToCloneWorker(context, graphId)) YIELD_REASON else null
    }

    override suspend fun postRun(context: Context, key: TaskKey, input: Data, result: TaskRunResult) {
        val graphId = key.scope.value
        val interval = input.getLong(GitSyncWorker.KEY_INTERVAL_MINUTES, WorkManagerSyncScheduler.MIN_PERIODIC_INTERVAL_MINUTES).toInt()
        val staleness = GitSyncServiceRegistry.getService(graphId)?.staleness?.value
            ?: SyncStalenessStore(PlatformSettings()).read(graphId)
        notifyUpdatesWaiting(context, graphId, staleness, interval)
    }

    /** Side-effecting observation only; never allowed to affect a worker result. */
    suspend fun runWatchdog(context: Context, graphId: String) {
        try {
            checkAndSurfaceStuckGitCloneWorker(context, graphId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // best effort
        }
    }

    /** Slow path only (no live service in this process): yield when a clone worker is already RUNNING. */
    suspend fun shouldYieldToCloneWorker(context: Context, graphId: String): Boolean =
        GitSyncServiceRegistry.getService(graphId) == null && isGitCloneWorkerRunningFor(context, graphId)

    /** Never affects the worker's own result: a notification problem must not make a good fetch look failed. */
    fun notifyUpdatesWaiting(context: Context, graphId: String, staleness: SyncStaleness, intervalMinutes: Int) {
        try {
            SyncNotifier(context, PlatformSettings()).onBackgroundTick(graphId, "", staleness, intervalMinutes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // best effort
        }
    }

    const val YIELD_REASON = "clone-running"
}
