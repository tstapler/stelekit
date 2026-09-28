// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import dev.stapler.stelekit.git.GitSyncBusyCounter
import dev.stapler.stelekit.model.StorageLocation
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 5.2.1c: [AndroidGraphMoveQuiesceStrategy.releaseSourceGrant] must release a [StorageLocation.SafFolder]
 * source's persisted `content://` tree URI permission — via the injected `releaseUriPermission`
 * lambda, the same fakes-over-Robolectric seam [AndroidGraphMoveQuiesceStrategyTest] uses for
 * `pauseBackgroundSync`/`resumeBackgroundSync` — and must never call it for a non-SAF source, since
 * only [StorageLocation.SafFolder] carries an OS-level grant to release at all.
 */
class SafGrantReleaseTest {

    @Test
    fun releaseSourceGrant_should_RemovePersistedUriPermission_When_CleanupConfirmed() = runTest {
        val releasedUris = mutableListOf<String>()
        val strategy = AndroidGraphMoveQuiesceStrategy(
            gitSyncBusyCounter = GitSyncBusyCounter(),
            shadowWorktreeTarget = { null },
            releaseUriPermission = { treeUri -> releasedUris += treeUri },
        )

        strategy.releaseSourceGrant(StorageLocation.SafFolder(graphId = "g1", treeUri = "content://tree/old"))

        assertEquals(listOf("content://tree/old"), releasedUris)
    }

    @Test
    fun releaseSourceGrant_should_NotCallReleaseUriPermission_When_SourceIsNotSafFolder() = runTest {
        val releasedUris = mutableListOf<String>()
        val strategy = AndroidGraphMoveQuiesceStrategy(
            gitSyncBusyCounter = GitSyncBusyCounter(),
            shadowWorktreeTarget = { null },
            releaseUriPermission = { treeUri -> releasedUris += treeUri },
        )

        strategy.releaseSourceGrant(StorageLocation.AppOwned(graphId = "g1"))

        assertTrue(releasedUris.isEmpty(), "AppOwned has no OS-level grant to release")
    }
}
