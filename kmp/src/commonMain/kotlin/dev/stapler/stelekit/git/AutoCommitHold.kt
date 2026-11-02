// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState

/** The sync path for `autoCommit = false` with local changes: fetch so the user sees what is waiting, never commit or merge. */
internal object AutoCommitHold {
    /**
     * Publishes [SyncState.Fetching], then either the fetch failure (through [onFetchFailed], which owns state
     * publishing for failures) or [SyncState.MergeAvailable]/[SyncState.Idle]. Returns the error the sync reports.
     */
    suspend fun fetchAndReport(
        repository: GitRepository,
        config: GitConfig,
        status: GitStatus,
        publish: (SyncState) -> Unit,
        onFetchFailed: (DomainError.GitError) -> Unit,
    ): DomainError.GitError {
        publish(SyncState.Fetching)
        when (val fetched = repository.fetch(config)) {
            is Either.Left -> {
                onFetchFailed(fetched.value)
                return fetched.value
            }
            is Either.Right -> publish(
                if (fetched.value.hasRemoteChanges) SyncState.MergeAvailable(fetched.value.remoteCommitCount) else SyncState.Idle,
            )
        }
        return DomainError.GitError.AutoCommitDisabled(status.untrackedFiles.size + status.modifiedFiles.size)
    }
}
