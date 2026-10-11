// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.Either
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * Task 3.1.3f: pre-3.3.2, [WasmJsGraphMoveQuiesceStrategy] has no real host-poll-pause wiring
 * yet, so [WasmJsGraphMoveQuiesceStrategy.quiesce] must complete immediately rather than suspend.
 */
class WasmJsGraphMoveQuiesceStrategyTest {

    private val op: StorageMoveOperation = StorageMoveOperation.Relocate(
        graphId = "g1",
        source = StorageLocation.AppOwned("g1"),
        destination = StorageLocation.HostFolder("g1", "Documents"),
        deleteSourceAfterVerify = true,
    )

    @Test
    fun quiesce_should_CompleteImmediately_When_NoHostPollPauseWiringExistsYet() = runTest {
        val strategy = WasmJsGraphMoveQuiesceStrategy()

        val result = strategy.quiesce(op) // must not suspend forever

        assertIs<Either.Right<Unit>>(result)
        strategy.release(op) // must not throw
        strategy.releaseSourceGrant(op.source) // must not throw
    }
}
