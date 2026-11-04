// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.withTimeoutOrNull

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

/** Builds the [FirstSyncPreview] for a graph, bounded by [FirstSyncPreview.TIMEOUT_MILLIS]. */
class FirstSyncPreviewSource(
    private val repository: GitRepository,
    private val configs: GitConfigRepository,
) {
    suspend fun load(graphId: String): Either<DomainError.GitError, FirstSyncPreview> {
        val config = when (val result = configs.getConfig(graphId)) {
            is Either.Left -> return DomainError.GitError.FetchFailed("Failed to load git config: ${result.value.message}").left()
            is Either.Right -> result.value ?: return DomainError.GitError.NotAGitRepo(graphId).left()
        }
        return withTimeoutOrNull(FirstSyncPreview.TIMEOUT_MILLIS) { repository.firstSyncPreview(config) }
            ?: DomainError.GitError.NetworkFailure("The preview took longer than ${FirstSyncPreview.TIMEOUT_MILLIS / MILLIS_PER_SECOND} s").left()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
