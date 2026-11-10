// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/** Default [LastSuccessSource]: the newest success recorded in the [TaskRunLog]. */
class TaskRunLogLastSuccessSource(private val runLog: TaskRunLog) : LastSuccessSource {
    override suspend fun lastSuccessAtMs(key: TaskKey): Long? = runLog.lastSuccessAtMs(key)
}
