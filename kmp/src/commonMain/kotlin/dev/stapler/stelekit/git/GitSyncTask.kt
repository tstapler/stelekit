// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.jobs.schedule.BackgroundTask
import dev.stapler.stelekit.jobs.schedule.JitterPreset
import dev.stapler.stelekit.jobs.schedule.Schedule
import dev.stapler.stelekit.jobs.schedule.TaskConfig
import dev.stapler.stelekit.jobs.schedule.TaskConstraints
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskKind
import dev.stapler.stelekit.jobs.schedule.TaskRunContext
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.jobs.schedule.TaskShape
import dev.stapler.stelekit.jobs.schedule.TaskSpec
import kotlinx.coroutines.CancellationException

/**
 * The closed-process fetch-only tick, supplied by androidMain (it needs the DB, driver and JGit that
 * commonMain cannot import). The clone-worker yield stays a `TaskAdapterHooks` preRun, not in here.
 */
fun interface ColdPathRunner {
    suspend fun run(graphId: String): TickOutcome
}

/**
 * Registers `git-sync` so Settings lists it and its default (including jitter) applies. Idempotent.
 * Shared by every composition root that builds a [dev.stapler.stelekit.jobs.schedule.TaskRegistry].
 */
fun dev.stapler.stelekit.jobs.schedule.TaskRegistry.registerGitSync(task: GitSyncTask = GitSyncTask()) {
    register(GitSyncTask.KIND, GitSyncTask.spec, task)
}

/**
 * `git-sync` behind [BackgroundTask]. [ScheduledSyncPolicy] stays the domain gate: the live tick is
 * `GitSyncService.scheduled.run`, which decides run / fetch-only / skip. With no live service (cold
 * process) the [cold] runner fetches only; without one the tick is a deliberate skip. The scope of the
 * key is the graph id.
 *
 * [liveTick] returns null when no service is registered for the graph.
 */
class GitSyncTask(
    private val liveTick: suspend (graphId: String) -> TickOutcome? = { graphId ->
        GitSyncServiceRegistry.getService(graphId)?.scheduled?.run(graphId)
    },
    private val cold: ColdPathRunner? = null,
) : BackgroundTask {
    override suspend fun run(key: TaskKey, ctx: TaskRunContext): TaskRunResult {
        val graphId = key.scope.value
        return try {
            val outcome = liveTick(graphId) ?: cold?.run(graphId) ?: return TaskRunResult.Skipped(NO_SERVICE)
            outcome.toTaskRunResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Transport retry already ran inside the git layer (ADR-002); a second retry layer must not stack.
            TaskRunResult.Failed(e::class.simpleName ?: "error", retryable = false)
        }
    }

    companion object {
        val KIND = TaskKind("git-sync")
        const val NO_SERVICE = "no-live-service"

        const val DEFAULT_INTERVAL_MINUTES = 60

        /** Disabled by default; jitter is "A little" (6 min of 60). Migrated graphs keep their own (0). */
        val defaultConfig = TaskConfig(
            enabled = false,
            schedule = Schedule.of(DEFAULT_INTERVAL_MINUTES, JitterPreset.ALittle.minutesFor(DEFAULT_INTERVAL_MINUTES)),
            constraints = TaskConstraints(),
        )

        val spec = TaskSpec(
            jobKind = JobKind.GitSync,
            title = "Git sync",
            shape = TaskShape.Periodic(defaultConfig.schedule),
            defaultConfig = defaultConfig,
            allowedInColdPath = true,
            oneShotRetryCap = 0,
        )
    }
}
