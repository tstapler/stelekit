// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import androidx.work.ListenableWorker

/**
 * Which WorkManager worker runs a kind, plus any extra input data it reads. The host supplies the
 * bindings (ADR-001: `jobs.schedule` never imports a consumer such as `git`); a kind without one runs
 * as [TaskWorker]. A kind whose existing jobs use another class must keep it, because a later `UPDATE`
 * with a different class would silently re-point them (ADR-002 outcome 4).
 */
class WorkerBinding(
    val workerClass: Class<out ListenableWorker>,
    /** Input data besides the network policy; `interval` is null for one-shot work. */
    val input: (key: TaskKey, intervalMinutes: Int?) -> Map<String, Any>,
)

fun interface WorkerClassResolver {
    fun bindingFor(kind: TaskKind): WorkerBinding?

    companion object {
        /** Every kind runs as [TaskWorker]. */
        val TaskWorkerOnly = WorkerClassResolver { null }

        fun of(bindings: Map<TaskKind, WorkerBinding>) = WorkerClassResolver { bindings[it] }
    }
}
