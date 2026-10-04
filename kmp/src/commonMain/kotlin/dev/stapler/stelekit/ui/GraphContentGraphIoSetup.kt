// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.stapler.stelekit.db.GraphEpoch
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.db.SidecarManager
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.repository.createGraphLoader
import kotlinx.coroutines.flow.first

/** Bundles [GraphContent]'s file/DB IO stack for the active graph (Parameter Object pattern). */
internal class GraphContentGraphIoStack(
    val sidecarManager: SidecarManager?,
    val imageSidecarManager: dev.stapler.stelekit.db.sidecar.ImageSidecarManager?,
    val imageImportService: dev.stapler.stelekit.db.ImageImportService?,
    val graphLoader: dev.stapler.stelekit.db.GraphLoader,
    val graphWriter: GraphWriter,
)

/**
 * Sets up the markdown-sidecar managers, the image-annotation import service, and the
 * [dev.stapler.stelekit.db.GraphLoader]/[GraphWriter] pair for the active graph. See
 * [rememberImageIoSetup] and [rememberGraphWriter] for the two halves' own docs.
 */
@Composable
internal fun rememberGraphContentGraphIoStack(
    deps: GraphContentDeps,
    effectiveFileSystem: FileSystem,
    activeGraphPath: String,
    activeGraphInfo: GraphInfo?,
    graphContentLogger: Logger,
): GraphContentGraphIoStack {
    val repos = deps.repos
    val imageIo = rememberImageIoSetup(effectiveFileSystem, repos, activeGraphPath, graphContentLogger)

    val sidecarManager = imageIo.sidecarManager
    val graphLoader = remember(effectiveFileSystem, repos, sidecarManager) {
        // graphId threads through to GraphFileWatcher so MoveInProgressFlag actually guards
        // this graph's watcher poll loop during a relocate/link (Story 1.3.2).
        repos.createGraphLoader(
            effectiveFileSystem,
            sidecarManager = sidecarManager,
            graphId = deps.graphManager.getActiveGraphId()?.value,
        ).also { loader ->
            loader.onCandidatesDiscovered = { candidates ->
                val gid = deps.graphManager.getActiveGraphId()
                if (gid != null) {
                    deps.graphManager.updateGraphCandidates(gid, candidates)
                }
            }
        }
    }
    WireGraphLoaderFlushCallbacks(effectiveFileSystem, graphLoader, repos)

    val graphWriter = rememberGraphWriter(effectiveFileSystem, repos, graphLoader, sidecarManager, activeGraphInfo)

    return GraphContentGraphIoStack(sidecarManager, imageIo.imageSidecarManager, imageIo.imageImportService, graphLoader, graphWriter)
}

/** The sidecar-manager/image-import-service half of [rememberGraphContentGraphIoStack]. */
private class ImageIoSetup(
    val sidecarManager: SidecarManager?,
    val imageSidecarManager: dev.stapler.stelekit.db.sidecar.ImageSidecarManager?,
    val imageImportService: dev.stapler.stelekit.db.ImageImportService?,
)

/**
 * Builds the markdown-sidecar and image-sidecar managers, then the image-annotation import
 * service — rebuilding annotations from sidecars once per graph open, but only when the DB has
 * none yet (avoids a full-scan on every open).
 */
@Composable
private fun rememberImageIoSetup(
    effectiveFileSystem: FileSystem,
    repos: RepositorySet,
    activeGraphPath: String,
    graphContentLogger: Logger,
): ImageIoSetup {
    val sidecarManager = remember(activeGraphPath, effectiveFileSystem) {
        val graphPath = activeGraphPath.ifEmpty { null }
        if (graphPath != null) SidecarManager(effectiveFileSystem, graphPath) else null
    }
    val imageSidecarManager = remember(activeGraphPath, effectiveFileSystem) {
        if (activeGraphPath.isNotEmpty()) dev.stapler.stelekit.db.sidecar.ImageSidecarManager(effectiveFileSystem) else null
    }
    val imageImportService = remember(imageSidecarManager) {
        if (imageSidecarManager != null && activeGraphPath.isNotEmpty()) {
            dev.stapler.stelekit.db.ImageImportService(
                fileSystem = effectiveFileSystem,
                imageAnnotationRepository = repos.imageAnnotationRepository,
                blockRepository = repos.blockRepository,
                sidecarManager = imageSidecarManager,
                journalService = repos.journalService,
                writeActor = repos.writeActor,
                measurementAnnotationRepository = repos.measurementAnnotationRepository,
            )
        } else null
    }
    LaunchedEffect(activeGraphPath) {
        reindexImageSidecarsIfEmpty(activeGraphPath, imageSidecarManager, effectiveFileSystem, repos, graphContentLogger)
    }
    return ImageIoSetup(sidecarManager, imageSidecarManager, imageImportService)
}

private suspend fun reindexImageSidecarsIfEmpty(
    activeGraphPath: String,
    imageSidecarManager: dev.stapler.stelekit.db.sidecar.ImageSidecarManager?,
    effectiveFileSystem: FileSystem,
    repos: RepositorySet,
    graphContentLogger: Logger,
) {
    if (activeGraphPath.isEmpty() || imageSidecarManager == null) return
    // Only rebuild if DB has no annotations — avoids full-scan on every open
    val hasExisting = repos.imageAnnotationRepository.getAllImageAnnotations()
        .first()
        .getOrNull()
        ?.isNotEmpty() == true
    if (hasExisting) return
    dev.stapler.stelekit.db.sidecar.ImageSidecarIndexer(
        fileSystem = effectiveFileSystem,
        imageAnnotationRepository = repos.imageAnnotationRepository,
        measurementAnnotationRepository = repos.measurementAnnotationRepository,
    ).rebuildFromSidecars(activeGraphPath)
        .onLeft { err -> graphContentLogger.warn("Sidecar reindex failed: ${err.message}") }
}

/**
 * Wires write-behind flush callbacks so FileRegistry correctly tracks SAF write windows.
 * - onFlushPreWrite: sets Long.MAX_VALUE sentinel before write, closing the mtime race window
 *   where a concurrent detectChanges poll emits a spurious event for .md.stek files.
 * - onFlushComplete: replaces sentinel with post-flush mtime after successful write.
 * - onFlushFailed: removes sentinel when write fails so the file is not permanently suppressed.
 */
@Composable
private fun WireGraphLoaderFlushCallbacks(
    effectiveFileSystem: FileSystem,
    graphLoader: dev.stapler.stelekit.db.GraphLoader,
    repos: RepositorySet,
) {
    remember(graphLoader, effectiveFileSystem) {
        effectiveFileSystem.setOnFlushPreWrite(graphLoader::preMarkFileWrite)
        effectiveFileSystem.setOnFlushComplete(graphLoader::markFileWrittenByUs)
        effectiveFileSystem.setOnFlushFailed(graphLoader::clearFilePendingWrite)
        // web-local-folder-livesync Epic 3.2 (Task 3.2.2d): wires HostDirectorySync's
        // reconciliation-conflict callback the same way as the three flush callbacks above —
        // GraphLoader only exists here (per-active-graph, inside this composition), never in
        // wasmJsMain's Main.kt, so this is where the plan's "after GraphLoader exists, wire the
        // callback" instruction actually applies. No-op on every platform but wasmJs.
        effectiveFileSystem.setOnHostConflict(graphLoader::emitExternalFileChange)
        // Bytes-aware sibling for `.md.stek` (paranoid-mode) HostOnlyNew content — see
        // FileSystem.setOnHostBytesConflict and GraphLoader.emitExternalFileChangeBytes.
        effectiveFileSystem.setOnHostBytesConflict(graphLoader::emitExternalFileChangeBytes)
        // web-local-folder-livesync Epic 4.4 (Task 4.4.1b): same wiring pattern, one call later —
        // forwards write-through failures onto GraphLoader's existing writeErrors channel.
        effectiveFileSystem.setOnHostWriteFailed(graphLoader::reportHostWriteFailure)
        // Feeds the disk-IO SLO (SloChecker): emits "file.write.deferred" spans for each
        // write-behind SAF flush so Android's deferred-write latency is tracked, not just
        // the near-instant markDirty enqueue.
        effectiveFileSystem.setSpanEmitter(repos.spanEmitter)
    }
}

/**
 * Builds the [GraphWriter] for the active graph and seeds its [GraphEpoch] immediately (bug fix,
 * sdd:6-verify BLOCKER): this instance is fresh per active graph (remember is keyed on [repos]),
 * and renamePage/deletePage fail fast on a null `currentEpoch` — so every graph needs one seeded
 * at construction, not only paranoid-mode graphs via onVaultUnlock/onCreateVault's success paths.
 * Those two handlers still advance `sequence` on top of this baseline via their own
 * `(graphWriter.currentEpoch?.sequence ?: 0L) + 1` logic.
 */
@Composable
private fun rememberGraphWriter(
    effectiveFileSystem: FileSystem,
    repos: RepositorySet,
    graphLoader: dev.stapler.stelekit.db.GraphLoader,
    sidecarManager: SidecarManager?,
    activeGraphInfo: GraphInfo?,
): GraphWriter = remember(effectiveFileSystem, repos, graphLoader, sidecarManager) {
    GraphWriter(
        effectiveFileSystem,
        repos.writeActor,
        onFileWritten = graphLoader::markFileWrittenByUs,
        sidecarManager = sidecarManager,
        onPreWrite = { filePath -> graphLoader.preMarkFileWrite(filePath) },
        onClearPendingWrite = { filePath -> graphLoader.clearFilePendingWrite(filePath) },
        checkPreWriteConflict = { filePath, diskContent ->
            val lastKnown = graphLoader.fileRegistry.getContentHash(dev.stapler.stelekit.model.FilePath(filePath))
            lastKnown != null && diskContent.hashCode() != lastKnown
        },
        onPreWriteConflict = { filePath, _, diskContent ->
            graphLoader.emitExternalFileChange(filePath, diskContent)
        },
        spanEmitter = repos.spanEmitter,
    ).also { writer ->
        val id = activeGraphInfo?.id
        if (id != null) {
            writer.currentEpoch = GraphEpoch(graphId = id, graphPath = activeGraphInfo.path, sequence = 1L)
        }
    }
}
