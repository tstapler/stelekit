// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import dev.stapler.stelekit.platform.HostDirectorySync
import dev.stapler.stelekit.platform.PlatformFileSystem
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
        // BLOCKER 2 defense-in-depth (PR #327 review): GraphRelocationCoordinator.relocate()/link()
        // now check MoveInProgressFlag before ever reaching here, so this should be unreachable in
        // practice — but an unconditional overwrite here would otherwise silently leak the prior
        // WebLock (its release() is never called) if this were ever invoked twice for the same
        // graphId before the first quiesce()'s matching release(). Fail fast instead of clobbering.
        if (heldLocks.containsKey(op.graphId)) {
            return DomainError.StorageError.SourceInFlight(
                "A relocate/link lock is already held for graph ${op.graphId}",
            ).left()
        }
        heldLocks[op.graphId] = WebLock.acquireHeld(relocateLockNameFor(op.graphId))
        hostDirectorySyncFor(op)?.pausePolling()
        return Unit.right()
    }

    override suspend fun release(op: StorageMoveOperation) {
        hostDirectorySyncFor(op)?.resumePolling()
        heldLocks.remove(op.graphId)?.release()
    }

    override suspend fun releaseSourceGrant(source: StorageLocation) {
        // No-op: Web has no SAF-style persisted URI permission to release (Epic 5.2 wired
        // ContentResolver.releasePersistableUriPermission for Android's SafFolder only).
    }
}

/**
 * Wires a [WasmJsGraphMoveQuiesceStrategy] to [fileSystem]'s real [PlatformFileSystem.hostDirectorySync]
 * — the composition root's job (Main.kt), since [WasmJsGraphMoveQuiesceStrategy]'s constructor is
 * `internal` to this file precisely so callers go through this factory rather than reaching for the
 * class directly, mirroring [createAndroidGraphMoveQuiesceStrategy]'s pattern on the other platform.
 * [fileSystem] holds only the currently-active graph's session (see [PlatformFileSystem.hostDirectorySync]'s
 * own doc), so — like [AndroidGraphMoveQuiesceStrategy]'s Context-scoped wiring — this strategy is
 * only ever correct for relocating the graph [fileSystem] is currently open on.
 */
fun createWasmJsGraphMoveQuiesceStrategy(fileSystem: PlatformFileSystem): WasmJsGraphMoveQuiesceStrategy =
    WasmJsGraphMoveQuiesceStrategy(hostDirectorySyncFor = { fileSystem.hostDirectorySync })
