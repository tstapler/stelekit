// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import android.content.Context
import android.content.Intent
import android.net.Uri
import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitSyncBusyCounter
import dev.stapler.stelekit.git.GitWriteBackQueue
import dev.stapler.stelekit.git.WorkManagerSyncScheduler
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import dev.stapler.stelekit.platform.GitWorktreeLocks
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/** Same read+write flag pair `MainActivity.takePersistableUriPermission` grants a tree URI with. */
private const val SAF_TREE_URI_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

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
 *
 * [pauseBackgroundSync]/[resumeBackgroundSync] (Story 3.2.1) pause/resume [op]'s graph's
 * `WorkManager` periodic fetch job so it never races the foreground copy of `.git` — injected as
 * lambdas rather than this class calling [WorkManagerSyncScheduler] directly, for the same
 * fakes-over-Robolectric testability reason as [shadowWorktreeTarget]. Default to no-ops so
 * existing callers/tests that don't care about WorkManager keep compiling unchanged; production
 * wiring goes through [createAndroidGraphMoveQuiesceStrategy].
 *
 * [releaseUriPermission] (Epic 5.2) is `ContentResolver.releasePersistableUriPermission`, injected
 * the same lambda way as the WorkManager pair above — this class needs no `Context` of its own to
 * stay unit-testable. Default is a no-op; [createAndroidGraphMoveQuiesceStrategy] wires the real
 * `ContentResolver` call.
 */
class AndroidGraphMoveQuiesceStrategy(
    private val gitSyncBusyCounter: GitSyncBusyCounter,
    private val shadowWorktreeTarget: (StorageMoveOperation) -> ShadowWorktreeQuiesceTarget?,
    private val pauseBackgroundSync: (graphId: String) -> Unit = {},
    private val resumeBackgroundSync: (graphId: String) -> Unit = {},
    private val releaseUriPermission: (treeUri: String) -> Unit = {},
) : GraphMoveQuiesceStrategy {

    /** Locks acquired by an in-flight [quiesce], keyed by shadow key, so [release] can find them. */
    private val heldLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun quiesce(op: StorageMoveOperation): Either<DomainError.StorageError, Unit> {
        pauseBackgroundSync(op.graphId)

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
        val shadowKey = shadowWorktreeTarget(op)?.shadowKey
        if (shadowKey != null) {
            heldLocks.remove(shadowKey)?.let { lock ->
                if (lock.isLocked) lock.unlock()
            }
        }
        resumeBackgroundSync(op.graphId)
    }

    override suspend fun releaseSourceGrant(source: StorageLocation) {
        if (source is StorageLocation.SafFolder) {
            releaseUriPermission(source.treeUri)
        }
    }
}

/**
 * Wires [AndroidGraphMoveQuiesceStrategy]'s [AndroidGraphMoveQuiesceStrategy.pauseBackgroundSync]/
 * [AndroidGraphMoveQuiesceStrategy.resumeBackgroundSync] to real [WorkManagerSyncScheduler] calls
 * (Story 3.2.1). Callers still supply [gitSyncBusyCounter] and [shadowWorktreeTarget] themselves —
 * this factory only adds the WorkManager pause/resume behavior on top.
 */
fun createAndroidGraphMoveQuiesceStrategy(
    context: Context,
    gitSyncBusyCounter: GitSyncBusyCounter,
    shadowWorktreeTarget: (StorageMoveOperation) -> ShadowWorktreeQuiesceTarget?,
): AndroidGraphMoveQuiesceStrategy = AndroidGraphMoveQuiesceStrategy(
    gitSyncBusyCounter = gitSyncBusyCounter,
    shadowWorktreeTarget = shadowWorktreeTarget,
    pauseBackgroundSync = { graphId -> WorkManagerSyncScheduler.pauseFor(context, graphId) },
    resumeBackgroundSync = { graphId -> WorkManagerSyncScheduler.resumeFor(context, graphId) },
    releaseUriPermission = { treeUri ->
        // Best-effort, matching MainActivity's existing release call (Task 5.2.1a) — a grant
        // that's already gone (revoked, or never actually taken) must not fail the relocate,
        // which has already completed successfully by the time this runs.
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(treeUri), SAF_TREE_URI_FLAGS)
        } catch (_: SecurityException) {
            // Grant was already released/revoked — nothing left to clean up.
        }
    },
)
