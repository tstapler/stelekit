// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.TaskKind
import dev.stapler.stelekit.jobs.schedule.WorkerBinding
import dev.stapler.stelekit.jobs.schedule.WorkerClassResolver

/** Maps `git-sync` to the existing [GitSyncWorker] and its input keys; `jobs.schedule` stays git-free (ADR-001). */
object GitSyncWorkerBinding {
    val kind = TaskKind("git-sync")

    val resolver: WorkerClassResolver = WorkerClassResolver.of(
        mapOf(
            kind to WorkerBinding(GitSyncWorker::class.java) { key, interval ->
                buildMap {
                    put(GitSyncWorker.KEY_GRAPH_ID, key.scope.value)
                    if (interval != null) put(GitSyncWorker.KEY_INTERVAL_MINUTES, interval.toLong())
                }
            },
        ),
    )
}
