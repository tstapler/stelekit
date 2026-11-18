// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig

/** Result of the closed-app run. */
sealed interface ColdSyncOutcome {
    /** One fetch succeeded; [behindCount] remote commits are waiting and nothing was merged. */
    data class Fetched(val behindCount: Int, val reason: FetchOnlyReason) : ColdSyncOutcome

    data class Error(val cause: DomainError.GitError) : ColdSyncOutcome

    /** The graph has no git configuration, so there is nothing to fetch. */
    data object NoConfig : ColdSyncOutcome
}

/**
 * The only thing a worker may do when no live [GitSyncService] exists (ADR-004): take the repository lock, run exactly
 * one fetch, and record the behind count. It never merges, commits, pushes, stages, checks out or aborts, so it cannot
 * touch the working tree whatever state that tree is in.
 *
 * It shares the process [gate] and never waits: a busy repository (or a [gateKeyFor] that cannot resolve the git
 * directory, which fails closed) is `Error(SyncInProgress)`, which callers map to a skip.
 */
class ColdSyncRunner(
    private val gitRepository: GitRepository,
    private val configRepository: GitConfigRepository,
    private val staleness: SyncStalenessStore,
    private val gate: RepoOpGate,
    private val gateKeyFor: (GitConfig) -> String?,
    private val clock: () -> Long,
) {
    suspend fun run(graphId: String): ColdSyncOutcome {
        val config = configRepository.getConfig(graphId).getOrNull() ?: return ColdSyncOutcome.NoConfig
        val key = gateKeyFor(config) ?: return busy(null)
        val lease = gate.tryAcquire(key, SyncOrigin.Cold) ?: return busy(gate.heldBy(key))
        try {
            staleness.setFetchOnlyReason(graphId, FetchOnlyReason.AppClosed)
            return gitRepository.fetch(config).fold(
                { err ->
                    staleness.recordError(graphId, clock(), err::class.simpleName ?: "GitError")
                    ColdSyncOutcome.Error(err)
                },
                { fetched ->
                    val behind = if (fetched.hasRemoteChanges) fetched.remoteCommitCount else 0
                    staleness.recordFetched(graphId, clock(), behind)
                    ColdSyncOutcome.Fetched(behind, FetchOnlyReason.AppClosed)
                },
            )
        } finally {
            // A cold run never presses, so no follow-up can be pending.
            gate.releaseOrHandOff(lease)
        }
    }

    private fun busy(heldBy: SyncOrigin?) = ColdSyncOutcome.Error(DomainError.GitError.SyncInProgress(heldBy))
}
