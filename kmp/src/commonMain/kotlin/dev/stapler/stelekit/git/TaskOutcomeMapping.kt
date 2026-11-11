// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.TaskRunResult

/**
 * A tick as the port's result. [TickOutcome.Fetched] is [TaskRunResult.Partial] (never "Last synced");
 * every [TickOutcome.Skip] stays a deliberate skip, never a failure, so it counts toward neither backoff
 * nor lateness. The `when`s are exhaustive so a new outcome or skip reason fails to compile here.
 */
fun TickOutcome.toTaskRunResult(): TaskRunResult = when (this) {
    TickOutcome.Success -> TaskRunResult.Success
    TickOutcome.Fetched -> TaskRunResult.Partial("fetched")
    is TickOutcome.Failure -> TaskRunResult.Failed(message, retryable = false)
    is TickOutcome.Skip -> TaskRunResult.Skipped(reason.code())
}

internal fun SkipReason.code(): String = when (this) {
    SkipReason.Busy -> "busy"
    SkipReason.Offline -> "offline"
    is SkipReason.Policy -> reason.code()
}

internal fun ScheduledSkipReason.code(): String = when (this) {
    ScheduledSkipReason.EditingInProgress -> "editing-in-progress"
    ScheduledSkipReason.Busy -> "busy"
    ScheduledSkipReason.VaultLocked -> "vault-locked"
    ScheduledSkipReason.ConflictPending -> "conflict-pending"
    ScheduledSkipReason.Backoff -> "backoff"
    ScheduledSkipReason.Offline -> "offline"
    ScheduledSkipReason.RepoState -> "repo-state"
    ScheduledSkipReason.FirstSyncUnconfirmed -> "first-sync-unconfirmed"
}
