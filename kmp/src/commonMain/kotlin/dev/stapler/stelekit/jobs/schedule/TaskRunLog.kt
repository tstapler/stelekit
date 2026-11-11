// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/** One run-log row. [source] says which single writer produced it. */
data class TaskRunRecord(
    val key: TaskKey,
    val source: RunSource,
    val scheduledAtMs: Long?,
    val startedAtMs: Long,
    val result: TaskRunResult,
    val constraintState: ConstraintState,
)

/** Outcome of [TaskRunLog.recentResult]; [ReadFailed] means "cannot read", which is not "no runs". */
sealed interface TaskRunReadResult {
    data class Loaded(val records: List<TaskRunRecord>) : TaskRunReadResult
    data class ReadFailed(val cause: Throwable) : TaskRunReadResult
}

/** Persisted, capped per-run records. Reads fail closed (empty / null); [append] never throws. */
interface TaskRunLog {
    suspend fun append(record: TaskRunRecord)

    /** Both sources merged, newest first. */
    suspend fun recent(key: TaskKey): List<TaskRunRecord>

    /** Like [recent] but a read error is [TaskRunReadResult.ReadFailed] instead of an empty list; for UI that offers Retry. */
    suspend fun recentResult(key: TaskKey): TaskRunReadResult = TaskRunReadResult.Loaded(recent(key))

    /** Newest successful run across both sources; independent of later skips and failures. */
    suspend fun lastSuccessAtMs(key: TaskKey): Long?
}
