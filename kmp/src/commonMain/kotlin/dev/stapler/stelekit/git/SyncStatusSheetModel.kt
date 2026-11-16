// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.toSyncErrorMessage
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.security.Redactor

data class SyncStatusRow(val label: String, val value: String)

/** Everything the standalone sync status sheet shows; the same chip and banner rules as the sidebar and launch banner. */
/** A failure or conflict the user must see; [target] says which detail surface resolves it (None: nothing to open). */
data class SyncProblem(val message: String, val target: SyncDetailsTarget)

/** What the sync row and sheet say about the run itself (ux.md S1); [Attention] leaves every other failure to [SyncStatusSheetModel.problem]. */
sealed interface SyncSheetState {
    data object Idle : SyncSheetState

    /** [phase] is the pipeline step when known; [followUpLine] is set only while a press is queued behind this run. */
    data class Running(val phase: String?, val followUpLine: String?) : SyncSheetState

    data class Paused(val rowText: String, val body: String, val details: String, val canRemoveLock: Boolean) : SyncSheetState

    data class Failed(val text: String, val canRemoveLock: Boolean) : SyncSheetState

    data object Attention : SyncSheetState
}

const val SYNC_PAUSED_ROW_TEXT = "Sync paused - retrying automatically"
const val SYNC_FAILED_TEXT = "Couldn't sync. Your notes are saved on this device."
const val SYNC_FOLLOW_UP_TEXT = "One more sync will run after this"
private const val SYNC_PAUSED_ANNOUNCEMENT = "Sync paused. Retrying automatically."

/** One polite live-region message per entry into Paused or Failed; a retry tick inside the same state says nothing. */
fun syncAnnouncement(previous: SyncSheetState?, current: SyncSheetState): String? = when {
    previous == null -> null
    current is SyncSheetState.Paused && previous !is SyncSheetState.Paused -> SYNC_PAUSED_ANNOUNCEMENT
    current is SyncSheetState.Failed && previous !is SyncSheetState.Failed -> SYNC_FAILED_TEXT
    else -> null
}

data class SyncStatusSheetModel(
    val headline: String,
    val severity: StalenessSeverity,
    val rows: List<SyncStatusRow>,
    /** Why scheduled work can only fetch, when it can. */
    val explanation: String?,
    val bannerChoice: BannerChoice,
    /** Newest first. */
    val recent: List<String>,
    val canSyncNow: Boolean,
    /** Why [canSyncNow] is false; null when Sync now can act. */
    val syncNowDisabledReason: String?,
    /** The conflict or error behind a stuck sync; null when the last sync did not fail. */
    val problem: SyncProblem?,
    val canReviewFirstSync: Boolean,
    /** Running, Paused, Failed, Idle or Attention; derived from the live run, the lock verdict and the pending retry. */
    val state: SyncSheetState = SyncSheetState.Idle,
    /** Hurries the pending automatic retry; non-null exactly while [state] is Paused (enabled iff a retry is pending). */
    val retryNow: (() -> Unit)? = null,
)

/** Pure function of the persisted [SyncStaleness] and the live [SyncState]; reuses [stalenessChipState] and [BannerPrecedence]. */
fun syncStatusSheetModel(
    staleness: SyncStaleness,
    syncState: SyncState,
    nowMs: Long,
    intervalMinutes: Int,
    firstSyncReviewPending: Boolean,
    contentMismatch: Boolean = false,
    /** The run holding the repository gate, if any (service `inFlight`). */
    inFlight: SyncRunInfo? = null,
    /** The service's last lock verdict; falls back to the one carried by the error. */
    lastVerdict: LockVerdict? = null,
    /** The automatic retry waiting after an unknown owner; null once the two tries are spent. */
    pausedRetry: PausedRetryInfo? = null,
    /** The service's "Retry now"; surfaced on the model only while a retry is pending. */
    retryNow: (() -> Unit)? = null,
): SyncStatusSheetModel {
    val chip = stalenessChipState(staleness, nowMs, intervalMinutes)
    val behind = staleness.behindCount
    val state = syncSheetState(syncState, inFlight, lastVerdict, pausedRetry, nowMs)
    val running = state is SyncSheetState.Running
    return SyncStatusSheetModel(
        headline = chip.text,
        severity = chip.severity,
        rows = listOf(
            SyncStatusRow("Last merged", staleness.lastMergedAtMs?.let { ageText(nowMs - it) } ?: "Never"),
            SyncStatusRow("Last checked", staleness.lastFetchedAtMs?.let { ageText(nowMs - it) } ?: "Never"),
            SyncStatusRow(
                "Behind remote",
                when {
                    behind == null -> "Checking…"
                    behind == 0 -> "Up to date"
                    else -> "$behind ${if (behind == 1) "commit" else "commits"}"
                },
            ),
        ),
        explanation = staleness.fetchOnlyReason?.let(::fetchOnlyExplanation),
        bannerChoice = BannerPrecedence.choose(
            mismatch = contentMismatch,
            firstSyncReview = firstSyncReviewPending,
            updatesWaiting = (behind ?: 0) > 0,
            reviewBadge = firstSyncReviewPending,
        ),
        recent = staleness.history.asReversed().map { recentLine(it, nowMs) },
        canSyncNow = !running,
        syncNowDisabledReason = if (running) "A sync is already running. Sync now is available once it finishes." else null,
        problem = if (state is SyncSheetState.Paused) null else syncState.toProblem(),
        canReviewFirstSync = firstSyncReviewPending,
        state = state,
        retryNow = if (state is SyncSheetState.Paused) retryNow else null,
    )
}

private const val MS_PER_MINUTE = 60_000L

/** What the sync row and sheet say about the run itself; shared by the sidebar row and the status sheet. */
fun syncSheetState(
    syncState: SyncState,
    inFlight: SyncRunInfo?,
    lastVerdict: LockVerdict?,
    pausedRetry: PausedRetryInfo?,
    nowMs: Long,
): SyncSheetState {
    if (inFlight != null || syncState.isRunning() || lastVerdict == LockVerdict.OwnerAlive) {
        val phase = when (syncState) {
            SyncState.Fetching -> "Fetching"
            SyncState.Merging -> "Merging"
            SyncState.Pushing -> "Pushing"
            SyncState.Committing -> "Committing"
            else -> null
        }
        return SyncSheetState.Running(phase, SYNC_FOLLOW_UP_TEXT.takeIf { inFlight?.followUpQueued == true })
    }
    val lock = (syncState as? SyncState.Error)?.error as? DomainError.GitError.StaleLockFile ?: return attentionOrIdle(syncState)
    val verdict = lastVerdict ?: lock.verdict
    val unsure = verdict == LockVerdict.NoRecord || verdict is LockVerdict.OwnerUnknown
    if (!unsure) return SyncSheetState.Attention
    val canRemoveLock = verdict == LockVerdict.NoRecord
    if (pausedRetry == null) return SyncSheetState.Failed(SYNC_FAILED_TEXT, canRemoveLock = true)
    val cause = if (canRemoveLock) "Leftover lock file" else "Lock owner unknown"
    return SyncSheetState.Paused(
        rowText = SYNC_PAUSED_ROW_TEXT,
        body = "We couldn't confirm that nothing else is syncing, so we paused to protect your notes. " +
            "We'll retry automatically (next try ${nextTryText(pausedRetry.nextRetryAtMs - nowMs)}). Your notes are saved on this device.",
        details = "$cause, try ${pausedRetry.tryNumber} of ${PausedRetryInfo.MAX_TRIES}",
        canRemoveLock = canRemoveLock,
    )
}

private fun attentionOrIdle(syncState: SyncState): SyncSheetState =
    if (syncState.toProblem() != null) SyncSheetState.Attention else SyncSheetState.Idle

private fun nextTryText(remainingMs: Long): String = when {
    remainingMs <= 0 -> "now"
    remainingMs < MS_PER_MINUTE -> "in under a minute"
    else -> "in ~${(remainingMs + MS_PER_MINUTE - 1) / MS_PER_MINUTE} min"
}

private fun fetchOnlyExplanation(reason: FetchOnlyReason): String = when (reason) {
    FetchOnlyReason.Saf -> "This graph lives in a folder SteleKit can only check for updates in the background. Tap Sync now to merge."
    FetchOnlyReason.AppClosed -> "While SteleKit is closed it only checks for updates. Open the app to merge."
}

private fun recentLine(outcome: BackgroundOutcome, nowMs: Long): String {
    val what = when (outcome.kind) {
        BackgroundOutcomeKind.Merged -> "Merged"
        BackgroundOutcomeKind.Fetched -> "Fetched, ${outcome.behindCount} behind"
        BackgroundOutcomeKind.Error -> if (outcome.detail.isBlank()) "Failed" else "Failed (${outcome.detail})"
    }
    return "$what · ${ageText(nowMs - outcome.atMs)}"
}

private fun SyncState.isRunning(): Boolean =
    this is SyncState.Fetching || this is SyncState.Merging || this is SyncState.Pushing || this is SyncState.Committing

private fun SyncState.toProblem(): SyncProblem? = when (this) {
    is SyncState.ConflictPending ->
        SyncProblem("Merge conflict in ${conflicts.size} ${if (conflicts.size == 1) "file" else "files"}. Nothing is merged until you resolve it.", SyncDetailsTarget.Conflict)
    is SyncState.JournalMergeReady -> SyncProblem("A journal merge needs your review before sync can finish.", SyncDetailsTarget.None)
    is SyncState.Error -> SyncProblem(error.describe(), detailsTarget())
    else -> null
}

private fun DomainError.GitError.describe(): String {
    val summary = toSyncErrorMessage()
    val detail = Redactor.redactGitMessage(message)
    return if (detail.isBlank() || summary.contains(detail)) summary else "$summary ($detail)"
}
