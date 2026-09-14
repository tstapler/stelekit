// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import kotlinx.coroutines.awaitCancellation

/**
 * Test double for [GraphMoveQuiesceStrategy] (Story 3.1.3), for exercising
 * `GraphRelocationCoordinator` (Story 3.1.5) without a real platform. [neverCompletes] models a
 * sync/write-back drain that hangs forever, so a test can assert the coordinator's own timeout —
 * not this fake — is what eventually terminates [quiesce]. [throwOnQuiesce], when set, models the
 * real platform implementations' documented ability to throw a raw `Throwable` from [quiesce]
 * rather than returning `Either.Left` (BLOCKER 3, PR #327 review).
 */
class FakeGraphMoveQuiesceStrategy(
    private val neverCompletes: Boolean = false,
    private val throwOnQuiesce: Throwable? = null,
) : GraphMoveQuiesceStrategy {
    val quiesceCalls = mutableListOf<StorageMoveOperation>()
    val releaseCalls = mutableListOf<StorageMoveOperation>()
    val releaseSourceGrantCalls = mutableListOf<StorageLocation>()

    override suspend fun quiesce(op: StorageMoveOperation): Either<DomainError.StorageError, Unit> {
        quiesceCalls += op
        throwOnQuiesce?.let { throw it }
        if (neverCompletes) awaitCancellation()
        return Unit.right()
    }

    override suspend fun release(op: StorageMoveOperation) {
        releaseCalls += op
    }

    override suspend fun releaseSourceGrant(source: StorageLocation) {
        releaseSourceGrantCalls += source
    }
}
