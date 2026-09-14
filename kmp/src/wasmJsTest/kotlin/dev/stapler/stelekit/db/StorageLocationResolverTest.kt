// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.StorageLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Story 1.1.4 (Task 1.1.4d): Web's [StorageLocationResolver] derivation branches, the
 * persisted-row short-circuit, and the "persists exactly once" guarantee — all exercised against
 * a [FakeStorageLocationStore] so no real `HostDirectorySync`/OPFS is needed.
 */
class StorageLocationResolverTest {

    private class FakeStorageLocationStore : StorageLocationStore {
        val rows = mutableMapOf<String, StorageLocation>()
        var writeCount = 0
            private set

        override suspend fun getStorageLocation(graphId: String): StorageLocation? = rows[graphId]

        override suspend fun onGraphLocationDetermined(graphId: String, location: StorageLocation) {
            writeCount++
            rows[graphId] = location
        }
    }

    @Test
    fun resolveOrBackfill_should_DeriveAndPersistHostFolder_When_HostDirectorySyncHandleConnected() = runTest {
        val store = FakeStorageLocationStore()
        val resolver = WasmJsStorageLocationResolver(
            store = store,
            connectedHostDisplayName = { "notes" },
        )

        val result = resolver.resolveOrBackfill("g8-connected")

        assertEquals(StorageLocation.HostFolder("g8-connected", "notes"), result)
        assertEquals(1, store.writeCount)
    }

    // REQ-3-style AppOwned branch, mirroring plan.md's g8 example.
    @Test
    fun resolveOrBackfill_should_DeriveAndPersistAppOwned_When_NoConnectedHostHandleAndNoRow() = runTest {
        val store = FakeStorageLocationStore()
        val resolver = WasmJsStorageLocationResolver(
            store = store,
            connectedHostDisplayName = { null },
        )

        val result = resolver.resolveOrBackfill("g8")

        assertEquals(StorageLocation.AppOwned("g8"), result)
        assertEquals(1, store.writeCount)
    }

    @Test
    fun resolveOrBackfill_should_ReturnPersistedRowUnchanged_When_RowAlreadyExists() = runTest {
        val store = FakeStorageLocationStore().apply {
            onGraphLocationDetermined("g12", StorageLocation.HostFolder("g12", "existing-folder"))
        }
        var deriveCalls = 0
        val resolver = WasmJsStorageLocationResolver(
            store = store,
            connectedHostDisplayName = { deriveCalls++; "should-not-be-used" },
        )

        val result = resolver.resolveOrBackfill("g12")

        assertEquals(StorageLocation.HostFolder("g12", "existing-folder"), result)
        assertEquals(0, deriveCalls)
        assertEquals(1, store.writeCount) // only the setup write above — no re-derivation write
    }

    @Test
    fun resolveOrBackfill_should_PersistExactlyOnceAndShortCircuitOnRecall_When_CalledTwiceForSameGraph() = runTest {
        val store = FakeStorageLocationStore()
        var deriveCalls = 0
        val resolver = WasmJsStorageLocationResolver(
            store = store,
            connectedHostDisplayName = { deriveCalls++; "notes" },
        )

        val first = resolver.resolveOrBackfill("g13")
        val second = resolver.resolveOrBackfill("g13")

        assertEquals(first, second)
        assertEquals(1, deriveCalls)
        assertEquals(1, store.writeCount)
    }
}
