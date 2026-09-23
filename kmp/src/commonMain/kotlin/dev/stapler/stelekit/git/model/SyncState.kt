// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.merge.JournalMergeProposal

sealed class SyncState {
    data object Idle : SyncState()
    data object Fetching : SyncState()
    data class MergeAvailable(val commitCount: Int) : SyncState()
    data object Merging : SyncState()
    data object Pushing : SyncState()
    data object Committing : SyncState()
    /** Emitted when sync requires credentials but the vault is locked. */
    data object CredentialVaultLocked : SyncState()
    data class ConflictPending(val conflicts: List<ConflictFile>) : SyncState()
    data class JournalMergeReady(val graphId: String, val proposal: JournalMergeProposal) : SyncState()
    data class Error(val error: DomainError.GitError) : SyncState()
    data class CredentialExpired(val graphId: String) : SyncState()
    /** Emitted when local edits exist but have not yet been synced to the remote. */
    data class LocalChangesPending(val fileCount: Int) : SyncState()
    /** Emitted when a git host rate limit was hit; sync will retry automatically. */
    data class RateLimited(val retryAfterSeconds: Int?) : SyncState()
    data class Success(
        val localCommitsMade: Int,
        val remoteCommitsMerged: Int,
        val lastSyncAt: Long,
    ) : SyncState()
}
