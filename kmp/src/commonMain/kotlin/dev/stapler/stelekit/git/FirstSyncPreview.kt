// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/** A remote branch the preview lists; [isSyncBranch] false means a stray the sync will not touch. */
data class PreviewRemoteBranch(val name: String, val isSyncBranch: Boolean)

/** A commit present locally but not on the remote-tracking branch. */
data class PreviewCommit(val shortSha: String, val subject: String, val timestampSeconds: Long)

/**
 * Read-only facts shown before the first sync of a `(remote, branch)`: how far apart the two sides are, which
 * remote branches exist, what would be pushed, and how many files the push would delete.
 * [ahead]/[behind] are null when the local history cannot say (for example a shallow clone).
 */
data class FirstSyncPreview(
    val syncRef: String,
    val ahead: Int?,
    val behind: Int?,
    val remoteBranches: List<PreviewRemoteBranch>,
    /** True when [remoteBranches] came from a live `ls-remote`; false means the locally known remote-tracking refs. */
    val remoteBranchesLive: Boolean,
    val localOnlyCommits: List<PreviewCommit>,
    val filesToDelete: Int,
) {
    companion object {
        const val MAX_LOCAL_COMMITS = 20

        /** Upper bound on building the preview; a slower one is reported as a timeout, never left spinning. */
        const val TIMEOUT_MILLIS = 15_000L
    }
}
