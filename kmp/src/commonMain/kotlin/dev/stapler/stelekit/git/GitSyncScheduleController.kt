// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.BackgroundTaskScheduler
import dev.stapler.stelekit.jobs.schedule.NetworkPolicy
import dev.stapler.stelekit.jobs.schedule.Schedule
import dev.stapler.stelekit.jobs.schedule.TaskConfig
import dev.stapler.stelekit.jobs.schedule.TaskEntry
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskOverrides
import dev.stapler.stelekit.jobs.schedule.TaskReadResult
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.jobs.schedule.TaskSettingsStore
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException

/**
 * The per-graph `git-sync` schedule behind Settings: reads and writes the graph's [TaskSettingsStore]
 * entry and keeps the scheduler in step with it. [BackgroundTaskScheduler.apply] runs ONLY when the
 * scheduler [runsWhenClosed][dev.stapler.stelekit.jobs.schedule.SchedulerCapabilities.runsWhenClosed]
 * (Android WorkManager). On Desktop, `PeriodicSyncDriver` owns the git loop, so calling `apply` there
 * would start a second one; the entry is still persisted and the driver reads it from here.
 *
 * The first read of a graph runs the lazy legacy migration (the store's seed), so [ensureMigrated] and
 * [read] are the same operation; an unreadable seed is [TaskReadResult.ReadFailed] and is never
 * treated as "disabled" (nothing is applied or cancelled).
 */
class GitSyncScheduleController(
    private val store: TaskSettingsStore,
    private val scheduler: BackgroundTaskScheduler,
) {
    private val logger = Logger("GitSyncScheduleController")

    private fun keyOf(graphId: String) = TaskKey(GitSyncTask.KIND, TaskScope(graphId))

    /** Runs the legacy migration for [graphId] if it has not run; idempotent, safe to call on every graph open. */
    suspend fun ensureMigrated(graphId: String): TaskReadResult = store.readResult(keyOf(graphId))

    /** The resolved config; fail-closed to disabled when it cannot be read. */
    suspend fun read(graphId: String): TaskConfig = store.read(keyOf(graphId))

    /**
     * Migrates if needed, then pushes the stored config to the scheduler (level-triggered, so repeating
     * it is safe). Returns the config, or null when the store could not be read (nothing is applied).
     */
    suspend fun reconcile(graphId: String): TaskConfig? {
        val key = keyOf(graphId)
        val config = (store.readResult(key) as? TaskReadResult.Ok)?.config ?: return null
        applyIfCapable(key, config)
        return config
    }

    /**
     * Turns the schedule on or off. When enabling an entry that has no stored schedule, [defaultSchedule]
     * (the graph's configured interval) becomes its schedule instead of the kind default.
     */
    suspend fun setEnabled(graphId: String, enabled: Boolean, defaultSchedule: Schedule? = null): TaskConfig? =
        update(graphId) { entry, current ->
            val overrides = entry.overrides
                ?: if (enabled && defaultSchedule != null) TaskOverrides(defaultSchedule, current.constraints) else null
            TaskEntry(enabled, overrides)
        }

    /** Sets interval and jitter, keeping the current enabled flag and constraints. */
    suspend fun setSchedule(graphId: String, schedule: Schedule): TaskConfig? =
        update(graphId) { entry, current -> TaskEntry(entry.enabled, TaskOverrides(schedule, current.constraints)) }

    /** Sets the network requirement, keeping the current enabled flag and schedule. */
    suspend fun setNetwork(graphId: String, network: NetworkPolicy): TaskConfig? =
        update(graphId) { entry, current ->
            TaskEntry(entry.enabled, TaskOverrides(current.schedule, current.constraints.copy(network = network)))
        }

    private suspend fun update(graphId: String, change: (TaskEntry, TaskConfig) -> TaskEntry): TaskConfig? {
        val key = keyOf(graphId)
        val current = (store.readResult(key) as? TaskReadResult.Ok)?.config ?: return null
        val written = store.write(key, change(store.readEntry(key), current))
        if (!written) logger.warn("git-sync entry not persisted for $graphId")
        // Read back so the caller (and the scheduler) see what is stored, not what was requested.
        val stored = (store.readResult(key) as? TaskReadResult.Ok)?.config ?: return null
        applyIfCapable(key, stored)
        return stored
    }

    private suspend fun applyIfCapable(key: TaskKey, config: TaskConfig) {
        if (!scheduler.capabilities.runsWhenClosed) return
        try {
            scheduler.apply(key, config)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn("apply failed for ${key.scope.value}", t)
        }
    }
}
