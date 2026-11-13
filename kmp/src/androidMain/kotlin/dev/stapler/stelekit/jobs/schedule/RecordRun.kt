// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException

private val recordRunLogger = Logger("RecordRun")

/**
 * Appends [record] to [runLog] (which also emits the `TaskRun` log line). Write-only and needs neither
 * registry nor store. Guarded for `Throwable` (rethrows [CancellationException]) so a log failure never
 * changes a worker `Result`. Shared by `TaskWorker` and the thin `GitSyncWorker`.
 */
suspend fun recordRun(runLog: TaskRunLog, record: TaskRunRecord) {
    try {
        runLog.append(record)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        recordRunLogger.warn("run log write failed for ${TaskKeyEncoding.encode(record.key)}: ${record.result}", e)
    }
}
