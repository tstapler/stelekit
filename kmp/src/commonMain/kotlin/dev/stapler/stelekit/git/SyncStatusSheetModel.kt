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
)

/** Pure function of the persisted [SyncStaleness] and the live [SyncState]; reuses [stalenessChipState] and [BannerPrecedence]. */
fun syncStatusSheetModel(
    staleness: SyncStaleness,
    syncState: SyncState,
    nowMs: Long,
    intervalMinutes: Int,
    firstSyncReviewPending: Boolean,
    contentMismatch: Boolean = false,
): SyncStatusSheetModel {
    val chip = stalenessChipState(staleness, nowMs, intervalMinutes)
    val behind = staleness.behindCount
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
        canSyncNow = !syncState.isRunning(),
        syncNowDisabledReason = if (syncState.isRunning()) "A sync is already running. Sync now is available once it finishes." else null,
        problem = syncState.toProblem(),
        canReviewFirstSync = firstSyncReviewPending,
    )
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
