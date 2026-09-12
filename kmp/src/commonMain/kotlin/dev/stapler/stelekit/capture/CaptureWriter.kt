// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import arrow.core.getOrElse
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.util.FractionalIndexing
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/**
 * Shared capture write path (commonMain), extracted from `CaptureViewModel.performSave()`
 * (androidApp) so headless callers — a background poller, a socket listener, a desktop
 * hotkey popup — can append a quick-capture note to today's journal without depending on
 * any Android/UI class.
 */
object CaptureWriter {

    /**
     * Appends [text] as a new block on today's journal page: `ensureTodayJournal()` →
     * `saveBlock()` → `savePage()`, and nothing else.
     *
     * When [captureId] is non-null, the new block's UUID is derived from it rather than
     * freshly generated, so replaying the same capture (crash-then-resume, a lost-ack retry)
     * resolves through `insertBlock`'s `INSERT OR REPLACE` semantics to a single row instead
     * of a duplicate. `captureId == null` (the live hotkey-popup path) keeps today's behavior
     * of a fresh UUIDv7 per save.
     */
    suspend fun writeCapture(
        repoSet: RepositorySet,
        fileSystem: PlatformFileSystem,
        graphPath: String,
        text: String,
        captureId: String? = null,
    ): CaptureResult = try {
        val page = repoSet.journalService.ensureTodayJournal()

        val existingBlocks = repoSet.blockRepository
            .getBlocksForPage(page.uuid)
            .first()
            .getOrElse { return CaptureResult.Failed("Failed to load blocks: $it") }

        val now = Clock.System.now()
        val newBlock = Block(
            uuid = captureId?.let { BlockUuid(it) } ?: BlockUuid(UuidGenerator.generateV7()),
            pageUuid = page.uuid,
            content = text,
            position = FractionalIndexing.generateKeyBetween(
                existingBlocks.maxByOrNull { it.position }?.position, null
            ),
            createdAt = now,
            updatedAt = now,
        )

        saveBlockWithFallback(repoSet, newBlock)?.let { return it }

        // Bug 8 mitigation: flush the Markdown file after every actor write.
        val writer = GraphWriter(fileSystem, writeActor = repoSet.writeActor)
        writer.savePage(page, existingBlocks + newBlock, graphPath)
            .getOrElse { return CaptureResult.Failed("Save failed: $it") }

        CaptureResult.Saved(page)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CaptureResult.Failed(e.message ?: "Unknown error during capture")
    }

    /**
     * Bug 1 mitigation (ported verbatim from CaptureViewModel.kt:100-111): saves [block] via
     * [RepositorySet.writeActor] when present, catching a graph-switch-race
     * [ClosedSendChannelException]; falls back to a direct repository write otherwise. Returns
     * a [CaptureResult.Failed] to short-circuit on, or `null` on success.
     */
    private suspend fun saveBlockWithFallback(repoSet: RepositorySet, block: Block): CaptureResult.Failed? {
        val writeActor = repoSet.writeActor
        if (writeActor != null) {
            try {
                writeActor.saveBlock(block).getOrElse { return CaptureResult.Failed("Save failed: $it") }
            } catch (e: ClosedSendChannelException) {
                return CaptureResult.Failed("Graph switched during save — please retry")
            }
        } else {
            @OptIn(DirectRepositoryWrite::class)
            repoSet.blockRepository.saveBlock(block).getOrElse { return CaptureResult.Failed("Save failed: $it") }
        }
        return null
    }

    /**
     * Mirrors `CaptureTileService.onClick`'s no-active-graph / paranoid-mode-locked gate:
     * returns the [CaptureResult] a caller should short-circuit on, or `null` when capture is
     * permitted. Never touches a repository when refusing.
     */
    fun resolveCaptureAvailability(graphManager: GraphManager): CaptureResult? {
        if (graphManager.getActiveRepositorySet() == null) return CaptureResult.NoActiveGraph
        if (graphManager.getActiveGraphInfo()?.isParanoidMode == true) return CaptureResult.GraphLocked
        return null
    }

    /**
     * Headless entry point for non-UI callers (a background poller, a socket listener) —
     * independent of `CaptureController`. Resolves capture availability, then the active
     * graph's repository set and path, and delegates to [writeCapture].
     */
    suspend fun writeCaptureDirect(
        graphManager: GraphManager,
        fileSystem: PlatformFileSystem,
        text: String,
        captureId: String? = null,
    ): CaptureResult {
        resolveCaptureAvailability(graphManager)?.let { return it }
        val repoSet = graphManager.getActiveRepositorySet() ?: return CaptureResult.NoActiveGraph
        val graphPath = graphManager.getActiveGraphInfo()?.path ?: return CaptureResult.NoActiveGraph
        return writeCapture(repoSet, fileSystem, graphPath, text, captureId)
    }
}
