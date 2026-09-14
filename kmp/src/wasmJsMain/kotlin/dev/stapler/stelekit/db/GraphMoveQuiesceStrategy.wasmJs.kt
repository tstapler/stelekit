// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation

/**
 * Web [GraphMoveQuiesceStrategy] (Story 3.1.3). Starts as an initial no-op — Web has no
 * `GitSyncBusyCounter`/write-back-queue equivalent of Android's shadow worktree yet — and is
 * extended by Story 3.3.2 to pause `HostDirectorySync`'s host-poll loop before a relocate copies
 * a linked host folder's content.
 */
class WasmJsGraphMoveQuiesceStrategy : GraphMoveQuiesceStrategy {
    override suspend fun quiesce(op: StorageMoveOperation): Either<DomainError.StorageError, Unit> = Unit.right()

    override suspend fun release(op: StorageMoveOperation) {
        // No-op — see class doc; Story 3.3.2 adds real host-poll-pause release here.
    }

    override suspend fun releaseSourceGrant(source: StorageLocation) {
        // No-op stub on every platform until Epic 5.2 wires the real cleanup.
    }
}
