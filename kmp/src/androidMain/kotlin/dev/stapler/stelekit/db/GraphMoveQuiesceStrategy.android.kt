// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import android.content.Context
import android.content.Intent
import android.net.Uri
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitSyncBusyCounter
import dev.stapler.stelekit.git.GateLease
import dev.stapler.stelekit.git.GitWriteBackQueue
import dev.stapler.stelekit.git.RepoOpGate
import dev.stapler.stelekit.git.SyncOrigin
import dev.stapler.stelekit.git.WorkManagerSyncScheduler
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import dev.stapler.stelekit.platform.GitWorktreeLocks
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

/** Same read+write flag pair `MainActivity.takePersistableUriPermission` grants a tree URI with. */
private const val SAF_TREE_URI_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

/**
 * Everything [AndroidGraphMoveQuiesceStrategy] needs to serialize a relocate/link against a git
 * shadow worktree's concurrent activity: the shared lock key, the durable write-back queue to
 * drain, and a way to drain it (a caller-supplied `GitShadowFlushActor.flush()` reference, kept
 * as a lambda here so this file never needs a `Context` or a real `GitShadowWorktree`/`FileSystem`
 * to be unit-tested — see `AndroidGraphMoveQuiesceStrategyTest`).
 *
 * [flushWhileLocked] runs while quiesce already holds the worktree mutex, so it must be
 * `GitShadowFlushActor::flushLocked`, never `flush` (the mutex is not reentrant).
 */
class ShadowWorktreeQuiesceTarget(
    val shadowKey: String,
    val queue: GitWriteBackQueue,
    val flushWhileLocked: suspend () -> List<Either<DomainError.GitError, Unit>>,
)

/** What one [AndroidGraphMoveQuiesceStrategy.quiesce] holds: the gate lease and the worktree mutex, released together. */
private class QuiesceHold(private val gate: RepoOpGate, private val lease: GateLease?) {
    /** Set only once the mutex is actually held, so a cancelled `lock()` is never unlocked. */
    var lock: Mutex? = null

    fun release() {
        lock?.let { if (it.isLocked) it.unlock() }
        // Mutex first, so a trigger that wins the freed gate never meets a still-held worktree lock.
        lease?.let { gate.releaseOrHandOff(it) }
    }
}

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
 * [gateKeyFor] resolves [op] to the same canonical `RepoOpGate.keyFor(resolvedGitDir)` the sync service
 * uses (null: no git config or unresolvable `.git`, so no gate). [quiesce] takes [gate] before the worktree
 * mutex, so it never holds the mutex while waiting for a sync that needs it (ADR-001 decision 7).
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
    private val gate: RepoOpGate,
    private val gateKeyFor: suspend (StorageMoveOperation) -> String?,
    private val shadowWorktreeTarget: (StorageMoveOperation) -> ShadowWorktreeQuiesceTarget?,
    private val pauseBackgroundSync: (graphId: String) -> Unit = {},
    private val resumeBackgroundSync: (graphId: String) -> Unit = {},
    private val releaseUriPermission: (treeUri: String) -> Unit = {},
) : GraphMoveQuiesceStrategy {

    companion object {
        /**
         * Caps the write-back drain loop below so a persistently-failing flush (repeated SAF
         * write failures) can't busy-loop real disk I/O until the caller's outer 30s
         * `QUIESCE_TIMEOUT_MS` kills it with an opaque `QuiesceTimedOut` instead of an actionable
         * error (MUST FIX from the `app-owned-storage-clone` idiom review).
         */
        private const val MAX_WRITE_BACK_FLUSH_ATTEMPTS = 10

        /** Backoff between retryable write-back failures, so the bounded loop isn't a hot spin. */
        private const val WRITE_BACK_RETRY_DELAY_MS = 200L
    }

    /** Holds taken by an in-flight [quiesce], keyed by graph id, so [release] can find them. */
    private val holds = ConcurrentHashMap<String, QuiesceHold>()

    override suspend fun quiesce(op: StorageMoveOperation): Either<DomainError.StorageError, Unit> {
        // The lambda reads WorkInfo with a blocking get(); keep it off the caller's (possibly main) dispatcher.
        withContext(Dispatchers.IO) { pauseBackgroundSync(op.graphId) }

        val target = shadowWorktreeTarget(op)
        // Gate first: a running sync may need the worktree mutex, so it is never taken while waiting here.
        val lease = gateKeyFor(op)?.let { gate.acquire(it, SyncOrigin.Quiesce) }
        val hold = QuiesceHold(gate, lease)
        holds[op.graphId] = hold
        try {
            if (target != null) {
                val worktreeLock = GitWorktreeLocks.lockFor(target.shadowKey)
                worktreeLock.lock()
                hold.lock = worktreeLock
            }

            gitSyncBusyCounter.awaitIdle()

            if (target != null) {
                val drainError = drainWriteBack(target)
                if (drainError != null) return drainError.left()
            }
        } catch (e: Throwable) {
            // Includes the caller's timeout cancellation: neither the lease nor the mutex may leak.
            holds.remove(op.graphId)
            hold.release()
            throw e
        }

        return Unit.right()
    }

    /**
     * Drains [target]'s write-back queue, bounded by [MAX_WRITE_BACK_FLUSH_ATTEMPTS] (see that
     * constant's doc for why). Returns null on success, or the [DomainError.StorageError] to
     * surface: immediately on a [DomainError.GitError.WorkingTreeConcurrentEditDetected] (the
     * source isn't actually safe to copy yet — mirrors `AndroidGitRepository.merge()`/
     * `checkoutFile()`'s handling of the same [dev.stapler.stelekit.git.GitShadowFlushActor]
     * result), or once the attempt bound is exhausted while a retryable
     * [DomainError.GitError.WorkingTreeWriteBackFailed] keeps the queue non-empty.
     */
    private suspend fun drainWriteBack(target: ShadowWorktreeQuiesceTarget): DomainError.StorageError? {
        var attempts = 0
        while (!target.queue.isEmpty() && attempts < MAX_WRITE_BACK_FLUSH_ATTEMPTS) {
            attempts++
            val flushErrors = target.flushWhileLocked().flushErrors()

            flushErrors.filterIsInstance<DomainError.GitError.WorkingTreeConcurrentEditDetected>()
                .firstOrNull()
                ?.let { concurrentEdit ->
                    return DomainError.StorageError.SourceInFlight(
                        "concurrent edit detected during write-back quiesce: ${concurrentEdit.path}",
                    )
                }

            // Any remaining errors are WorkingTreeWriteBackFailed (transient SAF I/O) — retryable,
            // so back off briefly and loop again, bounded by MAX_WRITE_BACK_FLUSH_ATTEMPTS.
            if (!target.queue.isEmpty() && attempts < MAX_WRITE_BACK_FLUSH_ATTEMPTS) {
                delay(WRITE_BACK_RETRY_DELAY_MS)
            }
        }

        if (!target.queue.isEmpty()) {
            return DomainError.StorageError.SourceInFlight(
                "write-back queue still has ${target.queue.getAll().size} pending path(s) after " +
                    "$MAX_WRITE_BACK_FLUSH_ATTEMPTS flush attempts",
            )
        }

        return null
    }

    /**
     * Extracts the [DomainError.GitError] values out of a [dev.stapler.stelekit.git.GitShadowFlushActor.flush]
     * result list's failed entries — mirrors `AndroidGitRepository`'s private helper of the same
     * name/shape, which lives in a different `androidMain` file so isn't reusable directly here.
     */
    private fun List<Either<DomainError.GitError, Unit>>.flushErrors(): List<DomainError.GitError> =
        mapNotNull { (it as? Either.Left)?.value }

    override suspend fun release(op: StorageMoveOperation) {
        holds.remove(op.graphId)?.release()
        withContext(Dispatchers.IO) { resumeBackgroundSync(op.graphId) }
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
    gate: RepoOpGate,
    gateKeyFor: suspend (StorageMoveOperation) -> String?,
    shadowWorktreeTarget: (StorageMoveOperation) -> ShadowWorktreeQuiesceTarget?,
): AndroidGraphMoveQuiesceStrategy = AndroidGraphMoveQuiesceStrategy(
    gitSyncBusyCounter = gitSyncBusyCounter,
    gate = gate,
    gateKeyFor = gateKeyFor,
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
