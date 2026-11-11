// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.EffectiveSchedule
import dev.stapler.stelekit.jobs.schedule.LastSuccessSource
import dev.stapler.stelekit.jobs.schedule.ScheduleLateness
import dev.stapler.stelekit.jobs.schedule.SchedulerCapabilities
import dev.stapler.stelekit.jobs.schedule.SettingsReadHealthSource
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskRunLog
import dev.stapler.stelekit.jobs.schedule.lateness
import kotlinx.coroutines.CancellationException

/** What the status line needs beyond the in-app driver's status: lateness and unreadable background settings. */
data class ScheduleHealth(val lateness: ScheduleLateness, val settingsUnreadable: Boolean) {
    companion object {
        val Healthy = ScheduleHealth(ScheduleLateness.OnTime, settingsUnreadable = false)
    }
}

/**
 * Lateness comes only from [lateness] over [lastSuccess] (git: run log and foreground staleness), and is
 * computed only where [lateStatusAllowed], so Desktop never reports Late. Every read fails closed: a
 * throwing run log or health source yields [ScheduleHealth.Healthy] rather than a false Late.
 */
suspend fun computeScheduleHealth(
    key: TaskKey,
    enabled: Boolean,
    effectiveIntervalMinutes: Int,
    caps: SchedulerCapabilities,
    lastSuccess: LastSuccessSource,
    runLog: TaskRunLog,
    readHealth: SettingsReadHealthSource?,
    nowMs: Long,
): ScheduleHealth {
    if (!enabled || !lateStatusAllowed(caps)) return ScheduleHealth.Healthy
    return try {
        val recent = runLog.recent(key)
        val schedule = EffectiveSchedule(effectiveIntervalMinutes, jitterMinutes = 0, flexMinutes = null, offsetMinutes = 0, clamped = false)
        val unreadable = isSettingsUnreadable(
            lastFailureMs = readHealth?.lastFailureMs(),
            lastSuccessMs = lastSuccess.lastSuccessAtMs(key),
            lastRun = recent.firstOrNull()?.result,
        )
        ScheduleHealth(lateness(key, schedule, lastSuccess, nowMs, enabled, recent.map { it.result }), unreadable)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ScheduleHealth.Healthy
    }
}
