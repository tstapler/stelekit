// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.StorageLocation

/**
 * Read/write seam [StorageLocationResolver] uses to check for and persist a graph's
 * `storage_locations` row (Story 1.1.4). [GraphManager] implements this in production; tests
 * fake it so platform derivation logic is exercised without a real database.
 */
interface StorageLocationStore {
    suspend fun getStorageLocation(graphId: String): StorageLocation?
    suspend fun onGraphLocationDetermined(graphId: String, location: StorageLocation)
}

/**
 * Lazily backfills a persisted `storage_locations` row for a graph that predates this feature —
 * the first time a relocate/link flow needs a real *source* [StorageLocation] to work from
 * (Story 1.1.4, ADR-001). Runs at "Move storage location" invocation time, not at app-upgrade
 * migration time, so a pre-existing graph is never probed until something actually needs its
 * real location (see plan.md's "Decision — when it runs").
 *
 * A plain (non-`expect`) port rather than an `expect class`: only Android and Web have a
 * meaningful non-`AppOwned` derivation today (SAF/`MANAGE_EXTERNAL_STORAGE` and
 * `HostDirectorySync` respectively) — see `AndroidStorageLocationResolver` /
 * `WasmJsStorageLocationResolver`. Desktop/iOS have no such derivation yet and are not required
 * to provide one just to satisfy an `expect` declaration.
 */
interface StorageLocationResolver {
    /**
     * Returns [graphId]'s existing persisted row unchanged when one exists — no re-derivation,
     * no platform probe. Otherwise derives a real location from the platform's own on-record
     * state, persists it via [StorageLocationStore.onGraphLocationDetermined], and returns it —
     * so every call after the first short-circuits on the row written here.
     */
    suspend fun resolveOrBackfill(graphId: String): StorageLocation
}

/**
 * Shared short-circuit + write-back behind every platform's [StorageLocationResolver.resolveOrBackfill]:
 * return the persisted row when one exists, otherwise run [derive] exactly once, persist its
 * result, and return it.
 */
internal suspend fun resolveOrBackfillStorageLocation(
    store: StorageLocationStore,
    graphId: String,
    derive: suspend () -> StorageLocation,
): StorageLocation {
    store.getStorageLocation(graphId)?.let { return it }
    val derived = derive()
    store.onGraphLocationDetermined(graphId, derived)
    return derived
}
