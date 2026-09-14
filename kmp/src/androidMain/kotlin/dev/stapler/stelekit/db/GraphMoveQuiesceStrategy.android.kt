// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitSyncBusyCounter
import dev.stapler.stelekit.git.GitWriteBackQueue
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import dev.stapler.stelekit.platform.GitWorktreeLocks
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/**
 * Everything [AndroidGraphMoveQuiesceStrategy] needs to serialize a relocate/link against a git
 * shadow worktree's concurrent activity: the shared lock key, the durable write-back queue to
 * drain, and a way to drain it (a caller-supplied `GitShadowFlushActor.flush()` reference, kept
 * as a lambda here so this file never needs a `Context` or a real `GitShadowWorktree`/`FileSystem`
 * to be unit-tested — see `AndroidGraphMoveQuiesceStrategyTest`).
 */
class ShadowWorktreeQuiesceTarget(
    val shadowKey: String,
    val queue: GitWriteBackQueue,
    val flush: suspend () -> List<Either<DomainError.GitError, Unit>>,
)

/**
 * Wraps [GitWorktreeLocks], [GitSyncBusyCounter], and a graph's [GitWriteBackQueue] behind
 * [GraphMoveQuiesceStrategy] (Story 3.1.3) so `GraphRelocationCoordinator` (Story 3.1.5) never
 * references any of those `androidMain`-only types directly.
 *
 * [gitSyncBusyCounter] must be the same shared instance `GitSyncService` uses for the graph being
 * moved (see that class's own constructor doc) — otherwise [quiesce] can never observe an
 * in-flight sync. [shadowWorktreeTarget] resolves [op] to its git shadow-worktree state, or null
 * when [op] doesn't touch one (no git remote configured for this graph) — injected rather than
 * this class resolving `AndroidGitRepository.shadowWorktreeFor()` itself, so it stays
 * unit-testable with fakes; Story 3.2.1 wires the real resolution.
 */
class AndroidGraphMoveQuiesceStrategy(
    private val gitSyncBusyCounter: GitSyncBusyCounter,
    private val shadowWorktreeTarget: (StorageMoveOperation) -> ShadowWorktreeQuiesceTarget?,
) : GraphMoveQuiesceStrategy {

    /** Locks acquired by an in-flight [quiesce], keyed by shadow key, so [release] can find them. */
    private val heldLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun quiesce(op: StorageMoveOperation): Either<DomainError.StorageError, Unit> {
        val target = shadowWorktreeTarget(op)
        if (target != null) {
            val lock = GitWorktreeLocks.lockFor(target.shadowKey)
            lock.lock()
            heldLocks[target.shadowKey] = lock
        }

        gitSyncBusyCounter.awaitIdle()

        if (target != null) {
            while (!target.queue.isEmpty()) {
                target.flush()
            }
        }

        return Unit.right()
    }

    override suspend fun release(op: StorageMoveOperation) {
        val shadowKey = shadowWorktreeTarget(op)?.shadowKey ?: return
        heldLocks.remove(shadowKey)?.let { lock ->
            if (lock.isLocked) lock.unlock()
        }
    }

    override suspend fun releaseSourceGrant(source: StorageLocation) {
        // No-op stub — Epic 5.2 wires ContentResolver.releasePersistableUriPermission here.
    }
}
