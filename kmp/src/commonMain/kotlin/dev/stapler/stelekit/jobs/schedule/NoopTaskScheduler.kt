// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/**
 * Honest adapter for hosts with no background execution (iOS, Web, tests): every capability is
 * false, nothing is scheduled or persisted, and [describe] is always null. The Settings UI hides
 * the Background tasks section when [SchedulerCapabilities.schedulesWork] is false.
 */
class NoopTaskScheduler : BackgroundTaskScheduler {
    override val capabilities = SchedulerCapabilities(
        minIntervalMinutes = 1,
        minFlexMinutes = 0,
        supportsUnmetered = false,
        supportsCharging = false,
        supportsJitter = false,
        runsWhenClosed = false,
        schedulesWork = false,
    )

    override suspend fun apply(key: TaskKey, config: TaskConfig) = Unit
    override suspend fun runOnce(key: TaskKey, constraints: TaskConstraints, onConflict: OneShotConflict) = Unit
    override suspend fun cancel(key: TaskKey) = Unit
    override suspend fun cancelAll(scope: TaskScope) = Unit
    override suspend fun pause(scope: TaskScope) = Unit
    override suspend fun resume(scope: TaskScope) = Unit
    override suspend fun describe(key: TaskKey): TaskScheduleInfo? = null
}
