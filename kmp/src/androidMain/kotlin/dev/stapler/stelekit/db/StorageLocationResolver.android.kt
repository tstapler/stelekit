// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.platform.PlatformFileSystem

/**
 * Android derivation (Story 1.1.4): an on-record SAF tree URI wins and yields
 * [StorageLocation.SafFolder]; otherwise, when `MANAGE_EXTERNAL_STORAGE` is granted and a real
 * filesystem path is resolvable, yields [StorageLocation.DirectAccessFolder]; otherwise the graph
 * has no external grant at all and is [StorageLocation.AppOwned].
 *
 * [persistedSafTreeUri] and [resolveDirectAccessRealPath] are injected rather than this class
 * reading `ContentResolver`/`Environment` itself, so it's unit-testable with fakes — no
 * Robolectric or real `Context` required (see `StorageLocationResolverTest`). Use
 * [createAndroidStorageLocationResolver] to wire real Android APIs.
 */
class AndroidStorageLocationResolver(
    private val store: StorageLocationStore,
    private val persistedSafTreeUri: (graphId: String) -> String?,
    private val hasManageExternalStorageAccess: () -> Boolean,
    private val resolveDirectAccessRealPath: (graphId: String) -> String?,
) : StorageLocationResolver {
    override suspend fun resolveOrBackfill(graphId: String): StorageLocation =
        resolveOrBackfillStorageLocation(store, graphId) { deriveLocation(graphId) }

    private fun deriveLocation(graphId: String): StorageLocation {
        persistedSafTreeUri(graphId)?.let { return StorageLocation.SafFolder(graphId, it) }
        if (hasManageExternalStorageAccess()) {
            resolveDirectAccessRealPath(graphId)?.let {
                return StorageLocation.DirectAccessFolder(graphId, it)
            }
        }
        return StorageLocation.AppOwned(graphId)
    }
}

/**
 * Wires a [StorageLocationResolver] to real Android state: [GraphManager]'s on-record
 * `GraphInfo.path` (a `saf://<encoded-tree-uri>` string for SAF-backed graphs, per
 * [PlatformFileSystem.toSafRoot]), [PlatformFileSystem.isSafPermissionValid] to confirm the
 * grant still holds, and [PlatformFileSystem.resolveSafToRealPath] for the
 * `MANAGE_EXTERNAL_STORAGE` fast path.
 */
fun createAndroidStorageLocationResolver(
    graphManager: GraphManager,
    context: Context,
): StorageLocationResolver {
    fun graphPath(graphId: String): String? = graphManager.getGraphInfo(GraphId(graphId))?.path

    return AndroidStorageLocationResolver(
        store = graphManager,
        persistedSafTreeUri = { graphId ->
            val path = graphPath(graphId)
            if (path != null && path.startsWith("saf://")) {
                val encodedTreeUri = path.removePrefix("saf://").substringBefore("/")
                try {
                    val uri = Uri.parse(Uri.decode(encodedTreeUri))
                    uri.takeIf { PlatformFileSystem.isSafPermissionValid(context, it) }?.toString()
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
        },
        hasManageExternalStorageAccess = {
            Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
        },
        resolveDirectAccessRealPath = { graphId ->
            graphPath(graphId)?.let { PlatformFileSystem.resolveSafToRealPath(it, context) }
        },
    )
}
