// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import android.content.Context
import androidx.work.Data
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Everything a cold [TaskWorker] needs, built from a `Context` and process statics (no UI state). */
class TaskWorkerDeps(
    val registry: TaskRegistry,
    val store: TaskSettingsStore,
    val runLog: TaskRunLog,
    val network: NetworkSnapshotProvider,
    val knownScopes: KnownScopes,
    val hooks: (TaskKind) -> TaskAdapterHooks?,
    val health: SettingsReadHealth,
    val clock: () -> Long = System::currentTimeMillis,
)

/** Platform-neutral outcome of one [runTaskWork]; [TaskWorker] maps it to a WorkManager `Result`. */
sealed interface TaskWorkOutcome {
    data object Success : TaskWorkOutcome
    data object Retry : TaskWorkOutcome
    data class Failure(val error: String?) : TaskWorkOutcome

    /** The scope is gone from a successfully parsed registry: cancel own unique work, report success. */
    data object CancelOwnWork : TaskWorkOutcome
}

private val logger = Logger("TaskWorkRunner")

/**
 * Gate, run, record for one work item. Order: registry -> cold-path eligibility -> [KnownScopes] guard ->
 * by shape (periodic reads config via `readResult`; a one-shot reads no store and never `enabled`) ->
 * `preRun` hook -> `NetworkGate` -> body -> result mapping -> [recordRun] -> `postRun` hook.
 * Catches `Throwable` (rethrows [CancellationException]). Used by [TaskWorker] only.
 *
 * Skips are `success` for periodic work (next period retries) and bounded `retry` then terminal failure for
 * one-shots; skips and real failures share one budget: `spec.oneShotRetryCap` total attempts.
 */
internal suspend fun runTaskWork(
    context: Context,
    key: TaskKey,
    input: Data,
    runAttemptCount: Int,
    deps: TaskWorkerDeps,
    reportProgress: suspend (Int) -> Unit = {},
    promoteToForeground: suspend (TaskKind) -> Unit = {},
): TaskWorkOutcome {
    val startedAtMs = deps.clock()
    var constraintState = UNKNOWN_STATE
    var spec: TaskSpec? = null
    var result: TaskRunResult? = null
    try {
        spec = deps.registry.specFor(key.kind)
        val task = deps.registry.taskFor(key.kind)
        if (spec == null || task == null) {
            result = TaskRunResult.Skipped("task-not-registered")
            return OUTCOME_SUCCESS
        }
        val oneShot = spec.shape is TaskShape.OneShot

        if (!spec.allowedInColdPath) {
            result = TaskRunResult.Skipped("cold-path-not-allowed")
            return skipOutcome(oneShot, spec, "cold-path-not-allowed", runAttemptCount)
        }

        if (isScopeGone(key, deps)) return TaskWorkOutcome.CancelOwnWork

        val policy: NetworkPolicy
        if (oneShot) {
            policy = input.getString(TaskWorkKeys.KEY_NETWORK_POLICY)?.let(TaskWorkKeys::decode) ?: NetworkPolicy.Unmetered
        } else {
            when (val read = deps.store.readResult(key)) {
                is TaskReadResult.ReadFailed -> {
                    guardedSideEffect("record settings read failure") { deps.health.recordFailure(startedAtMs) }
                    result = TaskRunResult.Skipped("settings-unreadable")
                    return OUTCOME_SUCCESS
                }
                is TaskReadResult.Ok -> {
                    guardedSideEffect("clear settings read failure") { deps.health.clear() }
                    if (!read.config.enabled) {
                        result = TaskRunResult.Skipped("disabled")
                        return OUTCOME_SUCCESS
                    }
                    policy = read.config.constraints.network
                }
            }
        }

        val hooks = deps.hooks(key.kind)
        val hookSkip = hooks?.let {
            try {
                it.preRun(context, key, input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("preRun hook failed for ${key.kind.id}", e)
                null
            }
        }
        if (hookSkip != null) {
            result = TaskRunResult.Skipped(hookSkip)
            return skipOutcome(oneShot, spec, hookSkip, runAttemptCount)
        }

        val snapshot: NetworkSnapshot? = try {
            deps.network.snapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("network snapshot failed", e)
            null
        }
        // A throwing provider never proceeds, even for NetworkPolicy.Any: connectivity is unknown.
        val gate = if (snapshot == null) {
            NetworkGateResult.Skip(NetworkSkipReason.UnknownNetwork)
        } else {
            constraintState = ConstraintState(snapshot.online, snapshot.isMetered, charging = null)
            NetworkGate.evaluate(policy, snapshot.online, snapshot.isMetered)
        }
        if (gate is NetworkGateResult.Skip) {
            result = gate.toTaskRunResult()
            return skipOutcome(oneShot, spec, gate.reason.label, runAttemptCount)
        }

        if (oneShot) {
            try {
                promoteToForeground(key.kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("foreground promotion failed, continuing without it", e)
            }
        }

        val ctx = TaskRunContext(scheduledAtMs = null, startedAtMs = startedAtMs, appOpen = false, constraintState = constraintState, reportProgress = reportProgress)
        val bodyResult = try {
            task.run(key, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.error("task ${key.kind.id} threw", e)
            TaskRunResult.Failed(redactedFailureMessage(e), retryable = false)
        }
        result = bodyResult
        return mapResult(oneShot, spec, bodyResult, runAttemptCount)
    } catch (e: CancellationException) {
        if (result == null) result = TaskRunResult.Skipped("cancelled")
        throw e
    } catch (e: Throwable) {
        logger.error("task work failed for ${key.kind.id}", e)
        val failed = TaskRunResult.Failed(redactedFailureMessage(e), retryable = false)
        result = failed
        return TaskWorkOutcome.Failure(failed.message)
    } finally {
        val final = result
        if (final != null) {
            // NonCancellable: a cancelled worker still has to leave its run record (a suspending append would throw).
            withContext(NonCancellable) {
                recordRun(
                    deps.runLog,
                    TaskRunRecord(key, RunSource.Background, scheduledAtMs = null, startedAtMs = startedAtMs, result = final, constraintState = constraintState),
                )
                if (spec != null) {
                    deps.hooks(key.kind)?.let { hooks -> guardedSideEffect("postRun hook") { hooks.postRun(context, key, input, final) } }
                }
            }
        }
    }
}

private val UNKNOWN_STATE = ConstraintState(online = false, isMetered = null, charging = null)
private val OUTCOME_SUCCESS = TaskWorkOutcome.Success

private suspend fun isScopeGone(key: TaskKey, deps: TaskWorkerDeps): Boolean {
    if (key.scope.value.isEmpty()) return false
    val known = try {
        deps.knownScopes.read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn("known scopes unreadable; running the task", e)
        KnownScopesResult.Unknown
    }
    return known is KnownScopesResult.Known && key.scope !in known.scopes
}

/** Periodic skip = success (next period retries). One-shot skip = bounded retry, then terminal failure. */
private fun skipOutcome(oneShot: Boolean, spec: TaskSpec, reason: String, runAttemptCount: Int): TaskWorkOutcome = when {
    !oneShot -> TaskWorkOutcome.Success
    runAttemptCount + 1 < spec.oneShotRetryCap -> TaskWorkOutcome.Retry
    else -> TaskWorkOutcome.Failure("skipped: $reason")
}

private fun mapResult(oneShot: Boolean, spec: TaskSpec, result: TaskRunResult, runAttemptCount: Int): TaskWorkOutcome = when (result) {
    is TaskRunResult.Success, is TaskRunResult.Partial -> TaskWorkOutcome.Success
    is TaskRunResult.Skipped -> skipOutcome(oneShot, spec, result.reason, runAttemptCount)
    is TaskRunResult.Failed -> {
        // Only a one-shot has a retry budget; a failed periodic run waits for its next period (ADR-002).
        val canRetry = result.retryable && oneShot && runAttemptCount + 1 < spec.oneShotRetryCap
        if (canRetry) TaskWorkOutcome.Retry else TaskWorkOutcome.Failure(result.message)
    }
}

private inline fun guardedSideEffect(what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn("$what failed", e)
    }
}
