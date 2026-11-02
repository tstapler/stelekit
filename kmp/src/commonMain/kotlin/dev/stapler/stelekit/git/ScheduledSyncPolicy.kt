// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/** Why a scheduled tick ran no full sync. [fetchesAnyway] reasons still fetch (read-only); the rest make no git call. */
enum class ScheduledSkipReason(val fetchesAnyway: Boolean) {
    EditingInProgress(false),
    Busy(false),
    VaultLocked(false),
    ConflictPending(false),
    Backoff(false),
    Offline(false),
    RepoState(true),
    FirstSyncUnconfirmed(true),
}

/** Why a scheduled tick fetched without merging, committing or pushing. */
enum class FetchOnlyReason { Saf, AppClosed }

/** What one scheduler tick may do. */
sealed interface ScheduledSyncDecision {
    /** Full sync: commit per `autoCommit`, merge, push. */
    data object Run : ScheduledSyncDecision

    /** One fetch; the working tree is never touched. */
    data class FetchOnly(val reason: FetchOnlyReason) : ScheduledSyncDecision

    data class Skip(val reason: ScheduledSkipReason) : ScheduledSyncDecision
}

/**
 * Everything the policy reads. Each field is the safe-to-run value when true, except the `*Blocked` style
 * names, so a missing or failed read must be passed as the blocking value (fail closed).
 */
data class ScheduledSyncInputs(
    val online: Boolean,
    val vaultUnlocked: Boolean,
    val editing: Boolean,
    val busy: Boolean,
    val backoffActive: Boolean,
    /** An in-memory conflict or journal-merge review is open. */
    val conflictPending: Boolean,
    /** The repository's own state is clean (no `MERGING`, no unmerged paths). */
    val repositorySafe: Boolean,
    /** The graph lives in an app-private directory the process can write directly (not SAF). */
    val appOwnedPath: Boolean,
    /** A manual, previewed sync has succeeded for the current `(remote, branch)`. */
    val firstSyncConfirmed: Boolean,
    /** True when the live service (the app) is running the tick; false for the closed-app runner. */
    val appOpen: Boolean = true,
)

/**
 * Pure decision for a scheduler tick (ADR-004). The first matching rule wins, so a tick that is skipped
 * for a cheap reason makes no git call at all; only [ScheduledSkipReason.fetchesAnyway] reasons fetch.
 * Never returns [ScheduledSyncDecision.Run] while any input blocks.
 */
object ScheduledSyncPolicy {

    fun decide(i: ScheduledSyncInputs): ScheduledSyncDecision = when {
        i.busy -> ScheduledSyncDecision.Skip(ScheduledSkipReason.Busy)
        i.editing -> ScheduledSyncDecision.Skip(ScheduledSkipReason.EditingInProgress)
        !i.vaultUnlocked -> ScheduledSyncDecision.Skip(ScheduledSkipReason.VaultLocked)
        !i.online -> ScheduledSyncDecision.Skip(ScheduledSkipReason.Offline)
        i.conflictPending -> ScheduledSyncDecision.Skip(ScheduledSkipReason.ConflictPending)
        i.backoffActive -> ScheduledSyncDecision.Skip(ScheduledSkipReason.Backoff)
        !i.appOpen -> ScheduledSyncDecision.FetchOnly(FetchOnlyReason.AppClosed)
        !i.appOwnedPath -> ScheduledSyncDecision.FetchOnly(FetchOnlyReason.Saf)
        !i.repositorySafe -> ScheduledSyncDecision.Skip(ScheduledSkipReason.RepoState)
        !i.firstSyncConfirmed -> ScheduledSyncDecision.Skip(ScheduledSkipReason.FirstSyncUnconfirmed)
        else -> ScheduledSyncDecision.Run
    }

    /** Pause after [failures] consecutive failed full syncs: 1, 2, 4, ... minutes, capped at [MAX_BACKOFF_MS] (INFERRED values). */
    fun backoffMs(failures: Int): Long {
        if (failures <= 0) return 0L
        val doublings = (failures - 1).coerceAtMost(MAX_DOUBLINGS)
        return (BASE_BACKOFF_MS shl doublings).coerceAtMost(MAX_BACKOFF_MS)
    }

    private const val BASE_BACKOFF_MS = 60_000L
    private const val MAX_BACKOFF_MS = 30 * 60_000L
    private const val MAX_DOUBLINGS = 10
}
