// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/*
 * The body and record/notify steps shared by the generic `runTaskWork` and the thin, registry-free
 * `GitSyncWorker`. Each worker keeps its own gating order (their network and clone-yield rules differ on
 * purpose); only the steps that must behave identically live here. Both take no registry or store.
 */

private val stepsLogger = Logger("TaskWorkSteps")

/** Runs [task], turning any non-cancellation Throwable (OutOfMemoryError included) into a redacted, non-retryable failure. */
internal suspend fun runTaskBody(task: BackgroundTask, key: TaskKey, ctx: TaskRunContext): TaskRunResult = try {
    task.run(key, ctx)
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    stepsLogger.error("task ${key.kind.id} threw", e)
    TaskRunResult.Failed(redactedFailureMessage(e), retryable = false)
}

/**
 * Appends [record] (when [runLog] is available), then runs [postRun] with its result. Runs under
 * `NonCancellable` so a cancelled worker still leaves its record. Neither step can change the worker's
 * `Result`: a throwing log or notifier is logged and dropped (only cancellation is rethrown).
 */
internal suspend fun recordThenPostRun(
    runLog: TaskRunLog?,
    record: TaskRunRecord,
    postRun: (suspend (TaskRunResult) -> Unit)?,
) {
    withContext(NonCancellable) {
        if (runLog != null) recordRun(runLog, record)
        if (postRun != null) {
            try {
                postRun(record.result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                stepsLogger.warn("postRun failed for ${TaskKeyEncoding.encode(record.key)}", e)
            }
        }
    }
}

internal inline fun guardedSideEffect(what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        stepsLogger.warn("$what failed", e)
    }
}
