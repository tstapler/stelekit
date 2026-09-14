// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import arrow.core.Either
import dev.stapler.stelekit.model.StorageLocation
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for the app-owned-storage-clone spec-compliance sweep's highest-severity
 * finding: no `androidMain` construction site ever wired a [HostLinkStep], so
 * [GraphRelocationCoordinator.link] always fell through to its `step == null` fast-fail branch
 * (`DomainError.StorageError.DestinationNotWritable`) for every Android graph, even a git-cloned
 * one `StorageMoveChoiceDialog`'s gating correctly let choose "Link". [createAndroidHostLinkStep]
 * is the fix — see its doc comment for why it's a genuine no-op.
 *
 * [GraphRelocationCoordinatorLinkTest] (`businessTest`) separately covers
 * [GraphRelocationCoordinator.link]'s own conditional-persist mechanism this step relies on
 * (`persistsDestinationOnSuccess = false`), using a fake rather than this real Android step,
 * since `androidMain` isn't reachable from that source set.
 */
class HostLinkStepAndroidTest {

    @Test
    fun link_should_SucceedWithoutError_When_Called() = runTest {
        val step = createAndroidHostLinkStep()

        val result = step.link(
            existingOpfsPath = "/data/data/dev.stapler.stelekit/files/graphs/g1",
            destination = StorageLocation.AppOwned("g1"),
        )

        assertTrue(result is Either.Right, "expected link() to succeed, got $result")
    }

    @Test
    fun persistsDestinationOnSuccess_should_BeFalse_So_LinkNeverRepointsStorageLocations() {
        val step = createAndroidHostLinkStep()

        // Story 4.2.1: "Link never repoints the location of record — only Relocate does."
        assertFalse(step.persistsDestinationOnSuccess)
    }
}
