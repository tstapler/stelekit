// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit

import dev.stapler.stelekit.capture.OffGraphCapture
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.merge.BlockContentHash
import dev.stapler.stelekit.merge.MergeBlock
import dev.stapler.stelekit.merge.PageKey
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.GraphId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Removes the one block a share added. Open graph: delete + re-flush the page; any other graph:
 * through the one router, which splices the block out only if it is unedited (hash-checked).
 */
internal class CaptureUndo(private val app: SteleKitApplication) {

    suspend fun undo(record: RecentCapture): Boolean = try {
        val graphId = GraphId(record.graphId)
        if (app.graphManager?.getActiveRepositorySet() != null && app.graphManager?.getActiveGraphId() == graphId) {
            undoOpenGraph(record.captureId)
        } else {
            undoViaRouter(graphId, record)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private suspend fun undoOpenGraph(captureId: String): Boolean {
        val gm = app.graphManager ?: return false
        val repoSet = gm.getActiveRepositorySet() ?: return false
        val graphPath = gm.getActiveGraphInfo()?.path ?: return false
        val block = repoSet.blockRepository.getBlockByUuid(BlockUuid(captureId)).first().getOrNull() ?: return false
        val page = repoSet.pageRepository.getPageByUuid(block.pageUuid).first().getOrNull() ?: return false
        val actor = repoSet.writeActor ?: return false
        if (actor.deleteBlock(block.uuid, block.pageUuid).isLeft()) return false
        val remaining = repoSet.blockRepository.getBlocksForPage(page.uuid).first().getOrNull() ?: return false
        return GraphWriter(app.fileSystem, writeActor = actor).savePage(page, remaining, graphPath).isRight()
    }

    private suspend fun undoViaRouter(graphId: GraphId, record: RecentCapture): Boolean {
        val services = app.shareServices() ?: return false
        val uuid = OffGraphCapture.blockUuid(record.captureId)
        val expected = mapOf(uuid to BlockContentHash.of(MergeBlock(uuid, record.text)))
        val report = services.router.withWriter(graphId) { writer ->
            writer.removeBlocks(PageKey(record.journalPage, isJournal = true), setOf(uuid), expected)
        }.getOrNull() ?: return false
        return uuid in report.removed
    }
}

/** Undo for a recorded Back auto-save; marks the record undone only on success. */
internal suspend fun SteleKitApplication.undoCapture(record: RecentCapture): Boolean {
    val ok = CaptureUndo(this).undo(record)
    if (ok) recentCaptures.markUndone(record.captureId)
    return ok
}
