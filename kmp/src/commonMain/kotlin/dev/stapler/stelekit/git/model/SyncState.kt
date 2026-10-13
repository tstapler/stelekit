// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.lfs.LfsViolation
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
    /**
     * Non-fatal: [held] files sit at paths this repository stores with Git LFS, so they were left out of the
     * commit (never uploaded raw). Everything else synced normally; the held files stay untouched on disk.
     */
    data class LfsActionRequired(val held: List<LfsViolation>) : SyncState()
    /** Emitted when a git host rate limit was hit; sync will retry automatically. */
    data class RateLimited(val retryAfterSeconds: Int?) : SyncState()
    data class Success(
        val localCommitsMade: Int,
        val remoteCommitsMerged: Int,
        val lastSyncAt: Long,
        /** Wiki-relative paths merged automatically by the markdown merge engine during this sync. */
        val autoMergedPaths: List<String> = emptyList(),
    ) : SyncState()
}

/** Never [toString] a state directly: conflict/journal-merge states carry full note contents. */
internal fun SyncState.logSummary(): String = when (this) {
    is SyncState.ConflictPending -> "ConflictPending(files=${conflicts.size})"
    is SyncState.JournalMergeReady -> "JournalMergeReady"
    is SyncState.Error -> "Error(${error.message})"
    else -> toString()
}
