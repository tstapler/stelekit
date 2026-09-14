// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import dev.stapler.stelekit.platform.HostDirectorySync
import dev.stapler.stelekit.platform.WebLock
import dev.stapler.stelekit.platform.relocateLockNameFor

/**
 * Web [GraphMoveQuiesceStrategy] (Story 3.1.3). [quiesce] always holds [op]'s graph-scoped
 * relocate lock ([relocateLockNameFor], Story 3.3.1) for the duration of the operation —
 * excluding a concurrent Link/relocate on the same graph without over-blocking an unrelated
 * `GitWriteLock` push, which uses a distinct namespace. When [op]'s graph has a connected host
 * folder, Story 3.3.2 additionally pauses [HostDirectorySync]'s poll loop
 * ([HostDirectorySync.pausePolling]) so a poll tick's reconciliation can never race the relocate's
 * copy step; [release] resumes it.
 *
 * [hostDirectorySyncFor] resolves [op] to the live [HostDirectorySync] instance for its graph, or
 * `null` when that graph has none (browser-only graph, no host folder ever connected) — injected
 * rather than this class looking one up itself, mirroring
 * [dev.stapler.stelekit.db.AndroidGraphMoveQuiesceStrategy]'s `shadowWorktreeTarget` seam, so this
 * class stays unit-testable with a fake. Defaults to `{ null }` so existing callers/tests (e.g.
 * [WasmJsGraphMoveQuiesceStrategyTest], written pre-3.3.2) that construct this with no arguments
 * keep compiling — the relocate lock still applies to them, they just never pause polling.
 */
class WasmJsGraphMoveQuiesceStrategy internal constructor(
    private val hostDirectorySyncFor: (StorageMoveOperation) -> HostDirectorySync? = { null },
) : GraphMoveQuiesceStrategy {

    /** Locks acquired by an in-flight [quiesce], keyed by graphId, so [release] can find them. */
    private val heldLocks = mutableMapOf<String, WebLock.HeldLock>()

    override suspend fun quiesce(op: StorageMoveOperation): Either<DomainError.StorageError, Unit> {
        heldLocks[op.graphId] = WebLock.acquireHeld(relocateLockNameFor(op.graphId))
        hostDirectorySyncFor(op)?.pausePolling()
        return Unit.right()
    }

    override suspend fun release(op: StorageMoveOperation) {
        hostDirectorySyncFor(op)?.resumePolling()
        heldLocks.remove(op.graphId)?.release()
    }

    override suspend fun releaseSourceGrant(source: StorageLocation) {
        // No-op stub on every platform until Epic 5.2 wires the real cleanup.
    }
}
