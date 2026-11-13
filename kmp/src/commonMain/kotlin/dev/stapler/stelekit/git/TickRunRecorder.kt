// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.ConstraintState
import dev.stapler.stelekit.jobs.schedule.RunSource
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskRunLog
import dev.stapler.stelekit.jobs.schedule.TaskRunRecord
import dev.stapler.stelekit.jobs.schedule.TaskRunResult

/**
 * Write-only hook [PeriodicSyncDriver] calls once after each tick that ran (a busy skip never ran).
 * It can observe a tick but never change the driver's outcome, backoff or pause logic.
 */
fun interface TickRunRecorder {
    suspend fun record(startedAtMs: Long, result: TaskRunResult, constraintState: ConstraintState)

    companion object {
        val None = TickRunRecorder { _, _, _ -> }
    }
}

/**
 * Appends each in-app tick to [runLog] as a [RunSource.Foreground] record for [key]. Foreground has
 * exactly one writer (the driver), so it never races a worker's Background record for the same key.
 */
fun TaskRunLog.asForegroundRecorder(key: TaskKey): TickRunRecorder = TickRunRecorder { startedAtMs, result, state ->
    append(TaskRunRecord(key, RunSource.Foreground, scheduledAtMs = null, startedAtMs = startedAtMs, result = result, constraintState = state))
}
