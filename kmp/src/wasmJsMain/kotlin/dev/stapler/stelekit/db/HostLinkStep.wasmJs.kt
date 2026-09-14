// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.platform.HostAccessState
import dev.stapler.stelekit.platform.HostDirectorySync

private val logger = Logger("HostLinkStep.wasmJs")

/**
 * Wires a [HostLinkStep] to [hostDirectorySync]'s real [HostDirectorySync.connectHostDirectory] —
 * the composition root's job (Main.kt), mirroring [createWasmJsGraphMoveQuiesceStrategy]'s and
 * [createWasmJsStorageLocationResolver]'s identical factory pattern. Story 4.1.1's acceptance
 * criteria are satisfied by forwarding straight through: [connectHostDirectory] already runs the
 * full picker → [HostDirectorySync.runHostReconciliation] sequence unchanged, so this function adds
 * no parallel connect/reconcile logic of its own — only the [Either] adaptation
 * [GraphRelocationCoordinator] needs.
 */
internal fun createWasmJsHostLinkStep(hostDirectorySync: HostDirectorySync): HostLinkStep =
    HostLinkStep { existingOpfsPath, _ ->
        when (hostDirectorySync.connectHostDirectory(existingOpfsPath)) {
            HostAccessState.Granted -> Unit.right()
            else -> DomainError.StorageError.DestinationNotWritable(
                "Couldn't establish a live link to the selected folder",
            ).left()
        }
    }

/**
 * Task 4.1.2's composition-root glue for the "Unlink" action: detaches [hostDirectorySync]'s
 * currently-linked folder, then — only once that succeeds — persists [graphId]'s
 * `storage_locations` row back to [StorageLocation.AppOwned] via [onGraphLocationDetermined]
 * (Story 4.1.2's first acceptance criterion). [onGraphLocationDetermined] is injected as a plain
 * suspend lambda — production call sites pass `graphManager::onGraphLocationDetermined` — rather
 * than taking a [GraphManager] directly, so this function stays unit-testable with a recording
 * fake; no wasmJsTest in this codebase constructs a real [GraphManager]/SQL driver today (it needs
 * a Web Worker-backed OPFS driver Karma's headless Chrome environment isn't set up for), and this
 * function's own logic — "persist only after a successful detach" — doesn't need one to verify.
 */
internal suspend fun unlinkHostDirectoryAndPersist(
    hostDirectorySync: HostDirectorySync,
    graphId: String,
    onGraphLocationDetermined: suspend (graphId: String, location: StorageLocation) -> Either<DomainError, Unit>,
): Either<DomainError.StorageError, Unit> {
    val result = hostDirectorySync.unlinkHostDirectory()
    if (result is Either.Right) {
        onGraphLocationDetermined(graphId, StorageLocation.AppOwned(graphId)).onLeft {
            logger.warn("unlinkHostDirectoryAndPersist: failed to persist AppOwned storage location for graph $graphId: $it")
        }
    }
    return result
}
