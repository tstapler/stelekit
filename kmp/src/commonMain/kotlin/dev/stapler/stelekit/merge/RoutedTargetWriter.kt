// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.GraphId

/**
 * A [TargetWriter] whose every call goes through [router] for [target], so it takes the target's
 * `GraphWriteLock` and picks the active or off-graph writer per call. [MergeUndo] needs a synchronous
 * `writerFor`; this is how it still reverts through the one router.
 */
class RoutedTargetWriter(
    private val router: TargetWriterRouter,
    private val target: GraphId,
) : TargetWriter {
    override suspend fun readExisting(page: PageKey): Either<DomainError, MergePage?> =
        router.withWriter(target) { it.readExisting(page) }

    override suspend fun write(page: PageKey, merged: MergePage): Either<DomainError, WriteOutcome> =
        router.withWriter(target) { it.write(page, merged) }

    override suspend fun deletePageFile(page: PageKey, expectedHash: String): Either<DomainError, Unit> =
        router.withWriter(target) { it.deletePageFile(page, expectedHash) }

    override suspend fun fileHash(page: PageKey): Either<DomainError, String?> =
        router.withWriter(target) { it.fileHash(page) }

    override suspend fun removeBlocks(
        page: PageKey,
        uuids: Set<String>,
        expectedContentHashes: Map<String, String>,
    ): Either<DomainError, RemoveReport> = router.withWriter(target) { it.removeBlocks(page, uuids, expectedContentHashes) }
}
