// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.platform.Settings

/**
 * Persisted consent for the first sync against a `(remote, branch)` pair. A branch repair never
 * syncs by itself: it marks a review pending, and the user's manual sync that then succeeds is the
 * confirmation. The confirmed value is the pair itself, so changing the branch re-arms the review.
 */
class FirstSyncConfirmation(private val settings: Settings) {

    fun isConfirmed(graphId: String, remote: String, branch: String): Boolean =
        settings.getString(confirmedKey(graphId), "") == "$remote/$branch"

    /** Records a successful manual sync and clears the pending review and previous-branch memo. */
    fun confirm(graphId: String, remote: String, branch: String) {
        settings.putString(confirmedKey(graphId), "$remote/$branch")
        settings.putBoolean(pendingKey(graphId), false)
        settings.putString(previousBranchKey(graphId), "")
    }

    /** Called by a branch repair: [previousBranch] is what it replaced (for "Change back"). */
    fun markReviewPending(graphId: String, previousBranch: String?) {
        settings.putBoolean(pendingKey(graphId), true)
        settings.putString(previousBranchKey(graphId), previousBranch.orEmpty())
    }

    fun isReviewPending(graphId: String): Boolean = settings.getBoolean(pendingKey(graphId), false)

    fun previousBranch(graphId: String): String? =
        settings.getString(previousBranchKey(graphId), "").ifEmpty { null }

    private fun confirmedKey(graphId: String) = "git_first_sync_confirmed_$graphId"
    private fun pendingKey(graphId: String) = "git_first_sync_review_pending_$graphId"
    private fun previousBranchKey(graphId: String) = "git_first_sync_previous_branch_$graphId"
}

/** Who started a sync: only [Manual] (and the review dialog's Sync now) counts as consent. */
enum class SyncTrigger { Manual, Automatic }
