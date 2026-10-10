// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.platform.HostAccessState

/**
 * Web derivation (Story 1.1.4): a currently connected `HostDirectorySync` handle for the graph
 * yields [StorageLocation.HostFolder]; otherwise the graph's content lives only in OPFS with no
 * host folder connected, and it is [StorageLocation.AppOwned].
 *
 * [connectedHostDisplayName] is injected rather than this class reading a live
 * `HostDirectorySync`/`hostAccessStateFlow` directly, so it's unit-testable with fakes (see
 * `StorageLocationResolverTest`). Use [createWasmJsStorageLocationResolver] to wire the real
 * `HostAccessState`.
 */
class WasmJsStorageLocationResolver(
    private val store: StorageLocationStore,
    private val connectedHostDisplayName: (graphId: String) -> String?,
) : StorageLocationResolver {
    override suspend fun resolveOrBackfill(graphId: String): StorageLocation =
        resolveOrBackfillStorageLocation(store, graphId) { deriveLocation(graphId) }

    private fun deriveLocation(graphId: String): StorageLocation {
        connectedHostDisplayName(graphId)?.let { return StorageLocation.HostFolder(graphId, it) }
        return StorageLocation.AppOwned(graphId)
    }
}

/**
 * Wires a [StorageLocationResolver] to real Web state: a host folder counts as "connected" only
 * when [hostAccessState] currently reports [HostAccessState.Granted], in which case
 * [GraphManager]'s on-record `GraphInfo.hostDirName` (the real host-folder name last linked via
 * the File System Access API) supplies the display name.
 */
fun createWasmJsStorageLocationResolver(
    graphManager: GraphManager,
    hostAccessState: () -> HostAccessState,
): StorageLocationResolver = WasmJsStorageLocationResolver(
    store = graphManager,
    connectedHostDisplayName = { graphId ->
        if (hostAccessState() == HostAccessState.Granted) {
            graphManager.getGraphInfo(GraphId(graphId))?.hostDirName
        } else {
            null
        }
    },
)
