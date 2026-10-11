// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation

/**
 * Quiesces a graph's in-flight git sync/write-back activity before a relocate/link copies its
 * content, per Story 3.1.3. `GraphRelocationCoordinator` (Story 3.1.5) depends on this
 * `commonMain` port rather than `GitWorktreeLocks`/`GitSyncBusyCounter`/`GitWriteBackQueue`
 * directly — those three types are `androidMain`-only, and KMP's dependency direction is one-way
 * (platform depends on common, never the reverse), so a `commonMain` coordinator cannot reference
 * them.
 *
 * A plain (non-`expect`) port, following [StorageLocationResolver]'s precedent: only Android
 * ([AndroidGraphMoveQuiesceStrategy]) has real quiesce work today; Web's
 * [WasmJsGraphMoveQuiesceStrategy] starts as a no-op (extended by Story 3.3.2); Desktop/iOS need
 * no implementation yet and are not required to provide one just to satisfy an `expect`
 * declaration.
 */
interface GraphMoveQuiesceStrategy {
    /** Suspends until [op]'s source is safe to copy — no in-flight sync, no pending write-back. */
    suspend fun quiesce(op: StorageMoveOperation): Either<DomainError.StorageError, Unit>

    /** Releases whatever [quiesce] held for [op] (e.g. a per-graph lock), regardless of outcome. */
    suspend fun release(op: StorageMoveOperation)

    /**
     * Releases any persisted access grant (e.g. a SAF tree URI permission) for [source] once a
     * relocate away from it has been confirmed. A no-op on every platform until Epic 5.2 wires
     * the real cleanup (Android: `ContentResolver.releasePersistableUriPermission`).
     */
    suspend fun releaseSourceGrant(source: StorageLocation)
}
