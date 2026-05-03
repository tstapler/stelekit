// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

import dev.stapler.stelekit.error.DomainError

sealed class SyncState {
    data object Idle : SyncState()
    data object Fetching : SyncState()
    data class MergeAvailable(val commitCount: Int) : SyncState()
    data object Merging : SyncState()
    data object Pushing : SyncState()
    data object Committing : SyncState()
    data class ConflictPending(val conflicts: List<ConflictFile>) : SyncState()
    data class Error(val error: DomainError.GitError) : SyncState()
    data class Success(
        val localCommitsMade: Int,
        val remoteCommitsMerged: Int,
        val lastSyncAt: Long,
    ) : SyncState()
}
