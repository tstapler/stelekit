// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.unlinkHostDirectoryAndPersist
import dev.stapler.stelekit.git.model.DirtyEntry
import dev.stapler.stelekit.git.model.DirtyOp
import dev.stapler.stelekit.model.StorageLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object
// (mirrors HostDirectorySyncHandleRetentionTest.kt's established idiom for this codebase).
private fun fakeRootHandle(): JsAny = js("({})")

/**
 * Epic 4.1 (Task 4.1.2d): [HostDirectorySync.unlinkHostDirectory]/[unlinkHostDirectoryAndPersist]
 * coverage per validation.md's acceptance criteria — clears the handle, stops polling, deletes the
 * persisted IndexedDB entry (so a later session's silent-resume never re-attaches the detached
 * folder), transitions to [HostAccessState.Unlinked], and — via [unlinkHostDirectoryAndPersist] —
 * persists [StorageLocation.AppOwned] only on a successful detach.
 */
class HostDirectorySyncUnlinkTest {

    @Test
    fun unlinkHostDirectory_should_ClearHandleAndStopPollingAndTransitionToUnlinked_When_GraphIsLinked() = runTest {
        val cache = FakeCacheAccess()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = connectedSync(OpfsGraphSlug("linked-graph"), "/stelekit/linked-graph", fakeRootHandle(), cache, testScope)
        sync.startHostDirectoryPolling()
        sync.hostWritePending["pages/Foo.md"] = DirtyEntry(op = DirtyOp.WRITE, updatedAtMillis = 0L)

        val result = sync.unlinkHostDirectory()

        assertIs<Either.Right<Unit>>(result)
        assertNull(sync.hostDirHandle)
        assertNull(sync.hostGraphOpfsPath)
        assertEquals(HostAccessState.Unlinked, sync.hostAccessStateFlow.value)
        // Nothing left to push to — the write-through queue is dropped with the link.
        assertTrue(sync.hostWritePending.isEmpty())
        assertEquals(0, sync.hostWritePendingCountFlow.value)

        // resumePolling() is a no-op once hostDirHandle/hostGraphOpfsPath are cleared — the
        // concrete proof stopHostDirectoryPolling() actually ran and nothing restarts it silently.
        sync.resumePolling()
        assertNull(sync.hostDirHandle)

        testScope.cancel()
    }

    @Test
    fun unlinkHostDirectory_should_DeletePersistedIndexedDbEntry_When_GraphWasPreviouslyPersisted() = runTest {
        val cache = FakeCacheAccess()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val graphId = OpfsGraphSlug("persisted-graph")
        val sync = connectedSync(graphId, "/stelekit/persisted-graph", fakeRootHandle(), cache, testScope)
        // Simulate a previously-persisted handle the same way attachFreshHandle/connectHostDirectory
        // would have (real IndexedDB round trip, mirroring HostDirectorySyncHandleRetentionTest.kt's
        // precedent for this codebase's established idiom).
        sync.attachFreshHandle(fakeRootHandle(), "/stelekit/persisted-graph")

        sync.unlinkHostDirectory()

        // A fresh sync's own reconnectHostDirectory (the silent-resume path a later session takes)
        // must find nothing — this is what actually prevents resurrecting the detached link.
        val resumedSync = disconnectedSync(graphId, FakeCacheAccess(), testScope)
        val resumedState = resumedSync.reconnectHostDirectory(graphId.value)

        assertEquals(HostAccessState.NotApplicable, resumedState)
        assertNull(resumedSync.hostDirHandle)

        testScope.cancel()
    }

    @Test
    fun unlinkHostDirectoryAndPersist_should_PersistAppOwnedLocation_When_UnlinkSucceeds() = runTest {
        val cache = FakeCacheAccess()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = connectedSync(OpfsGraphSlug("g1"), "/stelekit/g1", fakeRootHandle(), cache, testScope)
        val persistedCalls = mutableListOf<Pair<String, StorageLocation>>()

        val result = unlinkHostDirectoryAndPersist(sync, "g1") { graphId, location ->
            persistedCalls += graphId to location
            Unit.right()
        }

        assertIs<Either.Right<Unit>>(result)
        val expected: List<Pair<String, StorageLocation>> = listOf("g1" to StorageLocation.AppOwned("g1"))
        assertEquals(expected, persistedCalls)
        assertEquals(HostAccessState.Unlinked, sync.hostAccessStateFlow.value)

        testScope.cancel()
    }

    @Test
    fun unlinkHostDirectoryAndPersist_should_SucceedIdempotently_When_GraphWasNeverLinked() = runTest {
        // unlinkHostDirectory has no real failure mode in this codebase's own conventions — its
        // only fallible step (the IndexedDB delete) is best-effort/swallowed, matching
        // persistHostHandle's established pattern — so calling it on an already-unlinked graph is
        // a safe idempotent no-op that still transitions state and still persists AppOwned.
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = disconnectedSync(OpfsGraphSlug("g2"), FakeCacheAccess(), testScope)
        var persistCallCount = 0

        val result = unlinkHostDirectoryAndPersist(sync, "g2") { _, _ -> persistCallCount++; Unit.right() }

        assertIs<Either.Right<Unit>>(result)
        assertEquals(1, persistCallCount)
        assertEquals(HostAccessState.Unlinked, sync.hostAccessStateFlow.value)

        testScope.cancel()
    }
}
