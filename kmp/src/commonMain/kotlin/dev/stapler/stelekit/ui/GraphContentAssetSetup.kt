// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetIndexService
import dev.stapler.stelekit.asset.AssetIndexingController
import dev.stapler.stelekit.asset.AssetMoveService
import dev.stapler.stelekit.asset.IndexingMediaAttachmentService
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Moves [AssetEntry] into an `assets/` subfolder; see [AssetMoveService]. */
typealias AssetMover = suspend (AssetEntry, String) -> Either<DomainError, Unit>

/**
 * The per-graph asset stack: the indexing switch/controller, the move entry point, and the
 * attachment service that registers new files with the index.
 *
 * [moveAsset] is null when this build hides the asset switch (release variants before the heap
 * gate), so the Move action is not offered there.
 */
class GraphContentAssetStack(
    val indexing: AssetIndexingController,
    val moveAsset: AssetMover?,
    val attachmentService: dev.stapler.stelekit.service.MediaAttachmentService?,
)

/**
 * Constructs [AssetIndexService] and [AssetMoveService] for the active graph. Once the graph
 * reports fully loaded (so `loadGraph` never overlaps the walk) it starts the index backfill if
 * the `asset_pipeline_enabled` switch is on, and replays any move left half-done by a crash.
 * Leaving composition (graph switch) cancels the run and releases the service's scope.
 */
@Composable
internal fun rememberGraphContentAssetStack(
    deps: GraphContentDeps,
    effectiveFileSystem: FileSystem,
    graphIoStack: GraphContentGraphIoStack,
    appState: StateFlow<AppState>,
    logger: Logger,
): GraphContentAssetStack {
    val repos = deps.repos
    val delegateAttachments = deps.platformIntegrations.attachmentService
    val indexing = remember(repos, deps.platformSettings) {
        AssetIndexingController(deps.platformSettings, AssetIndexService(repos.assetRepository))
    }
    val moveService = remember(repos) { AssetMoveService(repos.assetRepository, repos.restrictedQueries) }
    DisposableEffect(indexing) { onDispose { indexing.close() } }

    LaunchedEffect(appState, indexing, moveService, effectiveFileSystem, graphIoStack) {
        appState.map { it.isFullyLoaded to it.currentNotesPath }.distinctUntilChanged().collectLatest { (loaded, notes) ->
            if (!loaded || notes == null) {
                indexing.onGraphNotReady()
                return@collectLatest
            }
            indexing.onGraphReady(notes, repos.writeActor, effectiveFileSystem)
            replayPendingMoves(moveService, notes, effectiveFileSystem, graphIoStack, repos.writeActor, logger)
        }
    }

    return remember(indexing, moveService, delegateAttachments, effectiveFileSystem, graphIoStack, appState) {
        val mover: AssetMover? = if (indexing.toggleVisible) {
            { asset, subfolder ->
                val notes = appState.value.currentNotesPath
                if (notes == null) {
                    DomainError.DatabaseError.WriteFailed("No active graph").left()
                } else {
                    moveService.moveAsset(asset, subfolder, notes, effectiveFileSystem, graphIoStack.graphWriter, repos.writeActor)
                }
            }
        } else {
            null
        }
        GraphContentAssetStack(
            indexing = indexing,
            moveAsset = mover,
            attachmentService = delegateAttachments?.let { IndexingMediaAttachmentService(it, indexing) },
        )
    }
}

/**
 * Not gated by the indexing switch: a crash mid-move must heal even if indexing was turned off
 * since. Reads only the (tiny) WAL table.
 */
private suspend fun replayPendingMoves(
    moveService: AssetMoveService,
    notes: dev.stapler.stelekit.model.EffectiveNotesPath,
    fileSystem: FileSystem,
    graphIoStack: GraphContentGraphIoStack,
    writeActor: dev.stapler.stelekit.db.DatabaseWriteActor?,
    logger: Logger,
) {
    try {
        moveService.replayPendingMoves(notes, fileSystem, graphIoStack.graphWriter, writeActor)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn("Pending asset move replay failed: ${e.message}")
    }
}
