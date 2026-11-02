// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Result of the closed-app run. */
sealed interface ColdSyncOutcome {
    /** One fetch succeeded; [behindCount] remote commits are waiting and nothing was merged. */
    data class Fetched(val behindCount: Int, val reason: FetchOnlyReason) : ColdSyncOutcome

    data class Error(val cause: DomainError.GitError) : ColdSyncOutcome

    /** The graph has no git configuration, so there is nothing to fetch. */
    data object NoConfig : ColdSyncOutcome
}

/** Process-wide named locks, so a closed-app fetch and any other holder of the same repository name never overlap. */
class NamedLocks {
    private val mutexes = MutableStateFlow<Map<String, Mutex>>(emptyMap())

    suspend fun <T> withLock(name: String, block: suspend () -> T): T {
        val mutex = mutexes.value[name] ?: run {
            val fresh = Mutex()
            mutexes.update { if (name in it) it else it + (name to fresh) }
            mutexes.value.getValue(name)
        }
        return mutex.withLock { block() }
    }
}

/**
 * The only thing a worker may do when no live [GitSyncService] exists (ADR-004): take the repository lock, run exactly
 * one fetch, and record the behind count. It never merges, commits, pushes, stages, checks out or aborts, so it cannot
 * touch the working tree whatever state that tree is in.
 */
class ColdSyncRunner(
    private val gitRepository: GitRepository,
    private val configRepository: GitConfigRepository,
    private val staleness: SyncStalenessStore,
    private val locks: NamedLocks,
    private val clock: () -> Long,
) {
    suspend fun run(graphId: String): ColdSyncOutcome {
        val config = configRepository.getConfig(graphId).getOrNull() ?: return ColdSyncOutcome.NoConfig
        return locks.withLock(GitWriteLockNaming.lockNameFor(config.repoRoot.value)) {
            staleness.setFetchOnlyReason(graphId, FetchOnlyReason.AppClosed)
            gitRepository.fetch(config).fold(
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
        }
    }
}
