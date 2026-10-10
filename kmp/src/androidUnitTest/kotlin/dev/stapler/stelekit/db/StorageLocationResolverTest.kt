// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.StorageLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Story 1.1.4 (Task 1.1.4d): Android's [StorageLocationResolver] derivation branches, the
 * persisted-row short-circuit, and the "persists exactly once" guarantee — all exercised against
 * a [FakeStorageLocationStore] so no real database/Context is needed.
 */
class StorageLocationResolverTest {

    private class FakeStorageLocationStore : StorageLocationStore {
        val rows = mutableMapOf<String, StorageLocation>()
        var writeCount = 0
            private set

        override suspend fun getStorageLocation(graphId: String): StorageLocation? = rows[graphId]

        override suspend fun onGraphLocationDetermined(graphId: String, location: StorageLocation): Either<DomainError, Unit> {
            writeCount++
            rows[graphId] = location
            return Unit.right()
        }
    }

    // REQ-3 (validation.md)
    @Test
    fun resolveOrBackfill_should_DeriveAndPersistSafFolder_When_OnRecordTreeUriExistsAndNoRow() = runTest {
        val store = FakeStorageLocationStore()
        val resolver = AndroidStorageLocationResolver(
            store = store,
            persistedSafTreeUri = { "content://.../Notes" },
            hasManageExternalStorageAccess = { false },
            resolveDirectAccessRealPath = { null },
        )

        val result = resolver.resolveOrBackfill("g7")

        assertEquals(StorageLocation.SafFolder("g7", "content://.../Notes"), result)
        assertEquals(StorageLocation.SafFolder("g7", "content://.../Notes"), store.rows["g7"])
        assertEquals(1, store.writeCount)
    }

    @Test
    fun resolveOrBackfill_should_DeriveAndPersistDirectAccessFolder_When_ManageExternalStorageGrantedAndRealPathResolves() =
        runTest {
            val store = FakeStorageLocationStore()
            val resolver = AndroidStorageLocationResolver(
                store = store,
                persistedSafTreeUri = { null },
                hasManageExternalStorageAccess = { true },
                resolveDirectAccessRealPath = { "/storage/emulated/0/Notes" },
            )

            val result = resolver.resolveOrBackfill("g9")

            assertEquals(StorageLocation.DirectAccessFolder("g9", "/storage/emulated/0/Notes"), result)
            assertEquals(1, store.writeCount)
        }

    @Test
    fun resolveOrBackfill_should_DeriveAndPersistAppOwned_When_NoTreeUriAndNoManageExternalStorageAccess() = runTest {
        val store = FakeStorageLocationStore()
        val resolver = AndroidStorageLocationResolver(
            store = store,
            persistedSafTreeUri = { null },
            hasManageExternalStorageAccess = { false },
            // Must not be consulted at all when MANAGE_EXTERNAL_STORAGE isn't granted.
            resolveDirectAccessRealPath = { error("should not be consulted") },
        )

        val result = resolver.resolveOrBackfill("g10")

        assertEquals(StorageLocation.AppOwned("g10"), result)
        assertEquals(1, store.writeCount)
    }

    @Test
    fun resolveOrBackfill_should_ReturnPersistedRowUnchanged_When_RowAlreadyExists() = runTest {
        val store = FakeStorageLocationStore().apply {
            onGraphLocationDetermined("g6", StorageLocation.SafFolder("g6", "content://existing"))
        }
        var deriveCalls = 0
        val resolver = AndroidStorageLocationResolver(
            store = store,
            persistedSafTreeUri = { deriveCalls++; "content://should-not-be-used" },
            hasManageExternalStorageAccess = { deriveCalls++; true },
            resolveDirectAccessRealPath = { deriveCalls++; "/should-not-be-used" },
        )

        val result = resolver.resolveOrBackfill("g6")

        assertEquals(StorageLocation.SafFolder("g6", "content://existing"), result)
        assertEquals(0, deriveCalls)
        assertEquals(1, store.writeCount) // only the setup write above — no re-derivation write
    }

    @Test
    fun resolveOrBackfill_should_PersistExactlyOnceAndShortCircuitOnRecall_When_CalledTwiceForSameGraph() = runTest {
        val store = FakeStorageLocationStore()
        var deriveCalls = 0
        val resolver = AndroidStorageLocationResolver(
            store = store,
            persistedSafTreeUri = { deriveCalls++; "content://.../Notes" },
            hasManageExternalStorageAccess = { false },
            resolveDirectAccessRealPath = { null },
        )

        val first = resolver.resolveOrBackfill("g11")
        val second = resolver.resolveOrBackfill("g11")

        assertEquals(first, second)
        assertEquals(1, deriveCalls)
        assertEquals(1, store.writeCount)
    }
}
