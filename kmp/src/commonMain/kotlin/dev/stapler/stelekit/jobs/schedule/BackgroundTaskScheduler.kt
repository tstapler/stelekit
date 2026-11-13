// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/**
 * The scheduling port. Level-triggered: [apply] states the desired periodic config and is idempotent.
 * [pause]/[resume] are an internal quiesce for clone and graph moves, never user-visible.
 */
interface BackgroundTaskScheduler {
    val capabilities: SchedulerCapabilities

    /** Periodic only: [TaskShape.OneShot] cannot reach here because [TaskConfig] holds a [Schedule]. */
    suspend fun apply(key: TaskKey, config: TaskConfig)

    /** One-shot entry. An explicit call always runs; `enabled` is not consulted. */
    suspend fun runOnce(key: TaskKey, constraints: TaskConstraints, onConflict: OneShotConflict = OneShotConflict.Keep)

    suspend fun cancel(key: TaskKey)
    suspend fun cancelAll(scope: TaskScope)
    suspend fun pause(scope: TaskScope)
    suspend fun resume(scope: TaskScope)

    /** Effective scheduling of [key] after platform clamping, or null when nothing is scheduled. */
    suspend fun describe(key: TaskKey): TaskScheduleInfo?
}
