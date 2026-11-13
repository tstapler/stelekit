// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.security.Redactor

/** Outcome of one background task run; neutral of any feature (no git types). */
sealed interface TaskRunResult {
    data object Success : TaskRunResult
    data class Partial(val detail: String) : TaskRunResult
    data class Skipped(val reason: String) : TaskRunResult
    data class Failed(val message: String, val retryable: Boolean = false) : TaskRunResult
}

/** Feature work registered per [TaskKind]; platform adapters call it, feature code never writes a Worker. */
fun interface BackgroundTask {
    suspend fun run(key: TaskKey, ctx: TaskRunContext): TaskRunResult
}

/** Failure text that is safe to store, log or put in WorkManager output: remote URLs and tokens are masked. */
fun redactedFailureMessage(t: Throwable): String = Redactor.redactGitMessage(t.message ?: t::class.simpleName ?: "error")
