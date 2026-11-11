// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.settings

import dev.stapler.stelekit.git.GitSyncTask
import dev.stapler.stelekit.git.scheduleForInterval
import dev.stapler.stelekit.git.scheduleForSpread
import dev.stapler.stelekit.jobs.schedule.BackgroundTaskScheduler
import dev.stapler.stelekit.jobs.schedule.JitterPreset
import dev.stapler.stelekit.jobs.schedule.NetworkPolicy
import dev.stapler.stelekit.jobs.schedule.Schedule
import dev.stapler.stelekit.jobs.schedule.SchedulerCapabilities
import dev.stapler.stelekit.jobs.schedule.TaskConfig
import dev.stapler.stelekit.jobs.schedule.TaskConstraints
import dev.stapler.stelekit.jobs.schedule.TaskDefaults
import dev.stapler.stelekit.jobs.schedule.TaskEntry
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskKind
import dev.stapler.stelekit.jobs.schedule.TaskOverrides
import dev.stapler.stelekit.jobs.schedule.TaskReadResult
import dev.stapler.stelekit.jobs.schedule.TaskRegistry
import dev.stapler.stelekit.jobs.schedule.TaskScheduleInfo
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.jobs.schedule.TaskSettingsStore
import dev.stapler.stelekit.jobs.schedule.TaskShape
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException

/** One (kind, graph) as Settings shows it. [base] is the kind default the row is compared against. */
sealed interface TaskSettingsLoad {
    data class Loaded(val entry: TaskEntry, val resolved: TaskConfig, val base: TaskConfig) : TaskSettingsLoad

    /** Unreadable: nothing is scheduled while this holds, and the UI offers Retry. */
    data object ReadFailed : TaskSettingsLoad
}

/** A single user change in the S2 panel. */
sealed interface PanelEdit {
    data class Enabled(val on: Boolean) : PanelEdit
    data class Interval(val minutes: Int) : PanelEdit
    data class Spread(val preset: JitterPreset) : PanelEdit
    data class Network(val policy: NetworkPolicy) : PanelEdit
    data object Reset : PanelEdit
}

/** Result of a user change; [Failed] means nothing was applied and the stored value is unchanged. */
enum class SaveResult { Saved, Failed }

/**
 * Settings-side writer for the per-kind, per-graph task schedule. Every change is
 * read, written, read back and applied once; a failed write or apply leaves the previous entry in place.
 *
 * `apply` runs only for periodic kinds, and for git sync only where the scheduler
 * [runsWhenClosed][SchedulerCapabilities.runsWhenClosed]: on Desktop `PeriodicSyncDriver` owns the git
 * loop and a second `apply` would start another (same rule as `GitSyncScheduleController`).
 */
class BackgroundTaskSettingsController(
    private val store: TaskSettingsStore,
    private val scheduler: BackgroundTaskScheduler,
    private val registry: TaskRegistry,
) {
    private val logger = Logger("BackgroundTaskSettingsController")

    val capabilities: SchedulerCapabilities get() = scheduler.capabilities

    fun titleOf(kind: TaskKind): String = registry.specFor(kind)?.title ?: kind.id

    fun isOneShot(kind: TaskKind): Boolean = registry.specFor(kind)?.shape == TaskShape.OneShot

    /** Kinds Settings lists, in registration order. */
    fun kinds(): List<TaskKind> = registry.kinds().toList()

    suspend fun load(key: TaskKey): TaskSettingsLoad {
        val result = store.readResult(key)
        if (result !is TaskReadResult.Ok) return TaskSettingsLoad.ReadFailed
        return TaskSettingsLoad.Loaded(store.readEntry(key), result.config, baseConfig(key.kind))
    }

    /** The kind default as a config: stored defaults over the spec default (disabled, since "Default for new graphs" is a template). */
    suspend fun baseConfig(kind: TaskKind): TaskConfig {
        val spec = registry.specFor(kind)?.defaultConfig ?: FALLBACK
        val defaults = store.readDefaults(kind) ?: return spec
        return TaskConfig(
            enabled = spec.enabled,
            schedule = defaults.schedule ?: spec.schedule,
            constraints = defaults.constraints,
        )
    }

    suspend fun describe(key: TaskKey): TaskScheduleInfo? = try {
        scheduler.describe(key)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("describe failed for ${key.scope.value}", e)
        null
    }

    /** Applies one panel change to a (kind, graph) entry, or to the app-wide one-shot entry. */
    suspend fun edit(key: TaskKey, edit: PanelEdit): SaveResult = when {
        isOneShot(key.kind) -> if (edit is PanelEdit.Network) setOneShotNetwork(key, edit.policy) else SaveResult.Failed
        else -> when (edit) {
            is PanelEdit.Enabled -> setEnabled(key, edit.on)
            is PanelEdit.Interval -> setInterval(key, edit.minutes)
            is PanelEdit.Spread -> change(key) { entry, current ->
                TaskEntry(entry.enabled, TaskOverrides(scheduleForSpread(current.schedule, edit.preset), current.constraints))
            }
            is PanelEdit.Network -> setNetwork(key, edit.policy)
            PanelEdit.Reset -> resetToDefault(key)
        }
    }

    /** The same changes against the "Default for new graphs" record. */
    suspend fun editDefaults(kind: TaskKind, edit: PanelEdit, followingGraphIds: List<String>): SaveResult {
        val current = baseConfig(kind)
        val next = when (edit) {
            is PanelEdit.Interval -> TaskDefaults(scheduleForInterval(current.schedule, edit.minutes), current.constraints)
            is PanelEdit.Spread -> TaskDefaults(scheduleForSpread(current.schedule, edit.preset), current.constraints)
            is PanelEdit.Network -> TaskDefaults(
                if (isOneShot(kind)) null else current.schedule,
                current.constraints.copy(network = edit.policy),
            )
            else -> return SaveResult.Failed
        }
        return writeDefaults(kind, next, followingGraphIds)
    }

    suspend fun setEnabled(key: TaskKey, enabled: Boolean): SaveResult =
        change(key) { entry, _ -> TaskEntry(enabled, entry.overrides) }

    suspend fun setInterval(key: TaskKey, minutes: Int): SaveResult = change(key) { entry, current ->
        TaskEntry(entry.enabled, TaskOverrides(scheduleFor(current.schedule, minutes), current.constraints))
    }

    suspend fun setSpread(key: TaskKey, schedule: Schedule): SaveResult = change(key) { entry, current ->
        TaskEntry(entry.enabled, TaskOverrides(schedule, current.constraints))
    }

    suspend fun setNetwork(key: TaskKey, network: NetworkPolicy): SaveResult = change(key) { entry, current ->
        TaskEntry(entry.enabled, TaskOverrides(current.schedule, current.constraints.copy(network = network)))
    }

    /** Clears the overrides, keeps `enabled`, applies once. */
    suspend fun resetToDefault(key: TaskKey): SaveResult = change(key) { entry, _ -> TaskEntry(entry.enabled, null) }

    /**
     * One-shot kinds (model download): only the network choice, stored as `enabled = false` with explicit
     * constraints, and never `apply` (an explicit `runOnce` always runs).
     */
    suspend fun setOneShotNetwork(key: TaskKey, network: NetworkPolicy): SaveResult = try {
        val constraints = (store.readEntry(key).overrides?.constraints ?: baseConfig(key.kind).constraints).copy(network = network)
        if (store.write(key, TaskEntry(enabled = false, overrides = TaskOverrides(schedule = null, constraints = constraints)))) {
            SaveResult.Saved
        } else {
            SaveResult.Failed
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("one-shot write failed for ${key.kind.id}", e)
        SaveResult.Failed
    }

    /**
     * Writes the "Default for new graphs" record (never a scope), then re-applies the graphs that
     * follow it so their schedule matches what Settings now shows.
     */
    suspend fun writeDefaults(kind: TaskKind, defaults: TaskDefaults, followingGraphIds: List<String> = emptyList()): SaveResult {
        val written = try {
            store.writeDefaults(kind, defaults)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!written) return SaveResult.Failed
        followingGraphIds.forEach { graphId ->
            val key = TaskKey(kind, TaskScope(graphId))
            val resolved = (store.readResult(key) as? TaskReadResult.Ok)?.config
            if (resolved != null && !applyFailed(key, resolved)) logger.debug("re-applied ${key.scope.value} after default change")
        }
        return SaveResult.Saved
    }

    private suspend fun change(key: TaskKey, edit: (TaskEntry, TaskConfig) -> TaskEntry): SaveResult {
        val current = (store.readResult(key) as? TaskReadResult.Ok)?.config ?: return SaveResult.Failed
        val previous = store.readEntry(key)
        val wrote = try {
            store.write(key, edit(previous, current))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!wrote) return SaveResult.Failed
        val stored = (store.readResult(key) as? TaskReadResult.Ok)?.config
        if (stored == null || applyFailed(key, stored)) {
            // No partially written state: put the previous entry back when the scheduler could not take the change.
            store.write(key, previous)
            return SaveResult.Failed
        }
        return SaveResult.Saved
    }

    private fun scheduleFor(current: Schedule, minutes: Int): Schedule = scheduleForInterval(current, minutes)

    /** True when applying threw; periodic-only and capability-gated (see class doc). */
    private suspend fun applyFailed(key: TaskKey, config: TaskConfig): Boolean {
        if (registry.specFor(key.kind)?.shape is TaskShape.OneShot) return false
        if (key.kind == GitSyncTask.KIND && !scheduler.capabilities.runsWhenClosed) return false
        return try {
            scheduler.apply(key, config)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn("apply failed for ${key.kind.id}/${key.scope.value}", t)
            true
        }
    }

    private companion object {
        val FALLBACK = TaskConfig(false, Schedule.of(60, 0), TaskConstraints())
    }
}
