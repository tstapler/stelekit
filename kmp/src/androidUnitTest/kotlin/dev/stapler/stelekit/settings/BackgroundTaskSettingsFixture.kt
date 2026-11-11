// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.settings

import dev.stapler.stelekit.git.GitSyncTask
import dev.stapler.stelekit.jobs.schedule.BackgroundTaskScheduler
import dev.stapler.stelekit.jobs.schedule.LegacyConfigSeed
import dev.stapler.stelekit.jobs.schedule.NetworkPolicy
import dev.stapler.stelekit.jobs.schedule.OneShotConflict
import dev.stapler.stelekit.jobs.schedule.Schedule
import dev.stapler.stelekit.jobs.schedule.SchedulerCapabilities
import dev.stapler.stelekit.jobs.schedule.TaskConfig
import dev.stapler.stelekit.jobs.schedule.TaskConstraints
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskKind
import dev.stapler.stelekit.jobs.schedule.TaskRegistry
import dev.stapler.stelekit.jobs.schedule.TaskScheduleInfo
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.jobs.schedule.TaskSettingsStore
import dev.stapler.stelekit.jobs.schedule.TaskShape
import dev.stapler.stelekit.jobs.schedule.TaskSpec
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.ui.components.settings.BackgroundTaskSettingsController

/** In-memory [Settings] whose reads and writes can be made to throw. */
class FailableSettings : Settings {
    val store = mutableMapOf<String, String>()
    var failReads = false
    var failWrites = false

    override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
    override fun getString(key: String, defaultValue: String): String {
        check(!failReads) { "read failed" }
        return store[key] ?: defaultValue
    }
    override fun putString(key: String, value: String) {
        check(!failWrites) { "write failed" }
        store[key] = value
    }
    override fun containsKey(key: String): Boolean {
        check(!failReads) { "read failed" }
        return store.containsKey(key)
    }
}

/** Counts `apply` calls and reports adapter-specific gap fields from `describe`. */
class RecordingScheduler(
    override val capabilities: SchedulerCapabilities,
    private val spread: (Schedule) -> Pair<Int, Int> = { it.intervalMinutes to it.intervalMinutes + it.jitterMinutes },
) : BackgroundTaskScheduler {
    val applied = mutableListOf<Pair<TaskKey, TaskConfig>>()
    var failApply = false
    var runOnceCalls = 0
    private val live = mutableMapOf<TaskKey, TaskConfig>()

    override suspend fun apply(key: TaskKey, config: TaskConfig) {
        check(!failApply) { "apply failed" }
        applied += key to config
        live[key] = config
    }

    override suspend fun runOnce(key: TaskKey, constraints: TaskConstraints, onConflict: OneShotConflict) { runOnceCalls++ }
    override suspend fun cancel(key: TaskKey) { live.remove(key) }
    override suspend fun cancelAll(scope: TaskScope) {}
    override suspend fun pause(scope: TaskScope) {}
    override suspend fun resume(scope: TaskScope) {}

    override suspend fun describe(key: TaskKey): TaskScheduleInfo? {
        val config = live[key] ?: return null
        val (min, max) = spread(config.schedule)
        val effective = maxOf(config.schedule.intervalMinutes, capabilities.minIntervalMinutes)
        return TaskScheduleInfo(effective, effective != config.schedule.intervalMinutes, null, min, max, appliesAfterNextRun = false)
    }
}

/** A Settings + store + scheduler + registry wired the way production wires them, for Compose tests. */
class BackgroundTaskFixture(
    val capabilities: SchedulerCapabilities,
    val seed: LegacyConfigSeed = LegacyConfigSeed.None,
) {
    val settings = FailableSettings()
    val registry = TaskRegistry().apply {
        register(GitSyncTask.KIND, GitSyncTask.spec, GitSyncTask())
        register(MODEL_DOWNLOAD, MODEL_DOWNLOAD_SPEC, NoopTask)
    }
    val scheduler = RecordingScheduler(capabilities)
    val store = TaskSettingsStore(settings, seed, registry)
    val controller = BackgroundTaskSettingsController(store, scheduler, registry)

    fun gitKey(graphId: String) = TaskKey(GitSyncTask.KIND, TaskScope(graphId))

    companion object {
        val MODEL_DOWNLOAD = TaskKind("model-download")
        val MODEL_DOWNLOAD_KEY = TaskKey(MODEL_DOWNLOAD, TaskScope(""))
        private val MODEL_DOWNLOAD_SPEC = TaskSpec(
            jobKind = null,
            title = "Model download",
            shape = TaskShape.OneShot,
            defaultConfig = TaskConfig(false, Schedule.of(60, 0), TaskConstraints(network = NetworkPolicy.Unmetered)),
            allowedInColdPath = false,
            oneShotRetryCap = 0,
        )

        val ANDROID = SchedulerCapabilities(
            minIntervalMinutes = 15, minFlexMinutes = 5, supportsUnmetered = true, supportsCharging = true,
            supportsJitter = true, runsWhenClosed = true, schedulesWork = true,
        )
        val DESKTOP = SchedulerCapabilities(
            minIntervalMinutes = 1, minFlexMinutes = 0, supportsUnmetered = false, supportsCharging = false,
            supportsJitter = true, runsWhenClosed = false, schedulesWork = true,
        )
        val NOOP = SchedulerCapabilities(
            minIntervalMinutes = 1, minFlexMinutes = 0, supportsUnmetered = false, supportsCharging = false,
            supportsJitter = false, runsWhenClosed = false, schedulesWork = false,
        )
    }
}

private object NoopTask : dev.stapler.stelekit.jobs.schedule.BackgroundTask {
    override suspend fun run(key: TaskKey, ctx: dev.stapler.stelekit.jobs.schedule.TaskRunContext) =
        dev.stapler.stelekit.jobs.schedule.TaskRunResult.Skipped("noop")
}
