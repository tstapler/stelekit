// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncStateTest {

    @Test
    fun `TC-1_3_2-A LocalChangesPending is a distinct sealed variant requiring an explicit when branch`() {
        val states: List<SyncState> = listOf(
            SyncState.Idle,
            SyncState.Fetching,
            SyncState.MergeAvailable(3),
            SyncState.Merging,
            SyncState.Pushing,
            SyncState.Committing,
            SyncState.CredentialVaultLocked,
            SyncState.ConflictPending(emptyList()),
            SyncState.Error(DomainError.GitError.Offline),
            SyncState.CredentialExpired("graph-1"),
            SyncState.LocalChangesPending(fileCount = 3),
            SyncState.RateLimited(retryAfterSeconds = 5),
            SyncState.Success(localCommitsMade = 1, remoteCommitsMerged = 0, lastSyncAt = 0L),
        )

        for (state in states) {
            // Exhaustive when — compile error if any branch (including LocalChangesPending) is missing.
            val label: String = when (state) {
                is SyncState.Idle -> "idle"
                is SyncState.Fetching -> "fetching"
                is SyncState.MergeAvailable -> "merge-available"
                is SyncState.Merging -> "merging"
                is SyncState.Pushing -> "pushing"
                is SyncState.Committing -> "committing"
                is SyncState.CredentialVaultLocked -> "credential-vault-locked"
                is SyncState.ConflictPending -> "conflict-pending"
                is SyncState.JournalMergeReady -> "journal-merge-ready"
                is SyncState.Error -> "error"
                is SyncState.CredentialExpired -> "credential-expired"
                is SyncState.LocalChangesPending -> "local-changes-pending"
                is SyncState.RateLimited -> "rate-limited"
                is SyncState.Success -> "success"
            }
            assertTrue(label.isNotEmpty())
        }

        val pending = SyncState.LocalChangesPending(fileCount = 3)
        assertEquals(3, pending.fileCount)
    }
}
