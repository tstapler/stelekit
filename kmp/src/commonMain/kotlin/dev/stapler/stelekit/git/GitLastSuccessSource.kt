// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.LastSuccessSource
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskRunLog
import kotlinx.coroutines.CancellationException

/**
 * Git's "last success" for lateness: the newest of the background [TaskRunLog] success and the
 * foreground-written [SyncStalenessStore] merge/fetch times, so a graph synced in the foreground never
 * reads Late. `Error` outcomes never count. Each source is guarded on its own: a read failure drops that
 * source (null when both fail), so an error can never surface as Late. The graph id is the key's scope.
 */
class GitLastSuccessSource(
    private val runLog: TaskRunLog,
    private val staleness: SyncStalenessStore,
) : LastSuccessSource {
    override suspend fun lastSuccessAtMs(key: TaskKey): Long? {
        val fromLog = guarded { runLog.lastSuccessAtMs(key) }
        val fromStaleness = guarded {
            val s = staleness.read(key.scope.value)
            listOfNotNull(s.lastMergedAtMs, s.lastFetchedAtMs).maxOrNull()
        }
        return listOfNotNull(fromLog, fromStaleness).maxOrNull()
    }

    private inline fun <T> guarded(block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}
