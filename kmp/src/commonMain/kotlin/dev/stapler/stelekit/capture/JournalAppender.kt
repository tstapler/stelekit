// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.RepositorySet
import kotlinx.coroutines.flow.first

/** Result of [JournalAppender.append]; idempotent on `captureId`. */
sealed interface AppendOutcome {
    /** The block was written. [writer] is the one that flushed the page, for post-save edits. */
    data class Appended(
        val saved: CaptureResult.Saved,
        val graphId: GraphId,
        val graphPath: String,
        val repoSet: RepositorySet,
        val writer: GraphWriter,
    ) : AppendOutcome

    /** Written straight to a graph that is not open; there is no repo set or writer to hand back. */
    data class AppendedOffGraph(val graphId: GraphId, val pagePath: String) : AppendOutcome

    /** A block with this `captureId` already exists; nothing was written. */
    data object AlreadyPresent : AppendOutcome

    /** Durably queued for later delivery. */
    data class Queued(val reason: String) : AppendOutcome

    /**
     * Nothing was written and nothing was queued; the caller decides (the share path queues it, the
     * inbox drain retries). [permanent] means retrying the same target cannot help (e.g. not round-trippable).
     */
    data class Deferred(val reason: String, val permanent: Boolean = false) : AppendOutcome

    /** [cause] keeps the specific [CaptureResult] (e.g. `NoActiveGraph`) for UI mapping. */
    data class Failed(val error: String, val cause: CaptureResult? = null) : AppendOutcome
}

/** Seam for appending to a graph that is not active; implemented by Story 4.1.3 via `TargetWriterRouter`. */
fun interface OffGraphAppendRoute {
    suspend fun append(graphId: GraphId, text: String, captureId: String?): AppendOutcome
}

/** An [OffGraphAppendRoute] that also carries a share's image. */
interface OffGraphContentRoute : OffGraphAppendRoute {
    suspend fun appendContent(graphId: GraphId, content: ShareContent, captureId: String?): AppendOutcome

    override suspend fun append(graphId: GraphId, text: String, captureId: String?): AppendOutcome =
        appendContent(graphId, ShareContent(text), captureId)
}

/**
 * Appends a block to today's journal in a [CaptureTarget]. The active-graph path delegates to
 * [CaptureWriter.writeCapture] (ensureTodayJournal -> saveBlock -> savePage); the block uuid is
 * the `captureId`, so replaying a capture yields [AppendOutcome.AlreadyPresent].
 *
 * Availability gating (e.g. a locked vault) stays with the caller, see
 * [CaptureWriter.resolveCaptureAvailability].
 */
class JournalAppender(
    private val graphManager: GraphManager,
    private val fileSystem: PlatformFileSystem,
    private val offGraphRoute: OffGraphAppendRoute? = null,
) {

    /** [writerFactory] lets a caller own the [GraphWriter] (e.g. to start autosave on it). */
    suspend fun append(
        target: CaptureTarget,
        text: String,
        captureId: String? = null,
        writerFactory: (RepositorySet) -> GraphWriter = { GraphWriter(fileSystem, writeActor = it.writeActor) },
    ): AppendOutcome = when (target) {
        CaptureTarget.ActiveGraph -> appendToActive(text, captureId, writerFactory).logged(target, WRITER_ACTIVE)
        is CaptureTarget.NamedGraph ->
            if (target.graphId == graphManager.getActiveGraphId()) {
                appendToActive(text, captureId, writerFactory).logged(target, WRITER_ACTIVE)
            } else {
                (offGraphRoute?.append(target.graphId, text, captureId) ?: AppendOutcome.Failed(NOT_SUPPORTED_YET))
                    .logged(target, WRITER_MARKDOWN)
            }
    }

    /**
     * Like [append] for a share: the image goes through an [OffGraphContentRoute], also for the open graph.
     * Without such a route an image share is a permanent [AppendOutcome.Deferred], never a dropped image.
     */
    suspend fun appendContent(
        target: CaptureTarget,
        content: ShareContent,
        captureId: String? = null,
        writerFactory: (RepositorySet) -> GraphWriter = { GraphWriter(fileSystem, writeActor = it.writeActor) },
    ): AppendOutcome {
        val route = offGraphRoute as? OffGraphContentRoute
        val offGraph = target is CaptureTarget.NamedGraph && target.graphId != graphManager.getActiveGraphId()
        if (!offGraph && content.image != null) {
            // The router hands out the real active writer, which stores the image under this graph's assets/.
            val graphId = (target as? CaptureTarget.NamedGraph)?.graphId ?: graphManager.getActiveGraphId()
                ?: return AppendOutcome.Failed("No active graph", CaptureResult.NoActiveGraph).logged(target, WRITER_ACTIVE)
            if (route == null) return AppendOutcome.Deferred(IMAGE_NEEDS_OPEN_GRAPH_UI, permanent = true).logged(target, WRITER_ACTIVE)
            return route.appendContent(graphId, content, captureId).logged(target, WRITER_ACTIVE)
        }
        if (!offGraph || route == null) return append(target, content.text, captureId, writerFactory)
        return route.appendContent((target as CaptureTarget.NamedGraph).graphId, content, captureId)
            .logged(target, WRITER_MARKDOWN)
    }

    /** Metrics line (M2/M3): ids and enum names only, never share text. */
    private fun AppendOutcome.logged(target: CaptureTarget, writer: String): AppendOutcome {
        val id = when (target) {
            is CaptureTarget.NamedGraph -> target.graphId.value
            CaptureTarget.ActiveGraph -> graphManager.getActiveGraphId()?.value ?: "none"
        }
        val name = when (this) {
            is AppendOutcome.Appended, is AppendOutcome.AppendedOffGraph -> "Appended"
            AppendOutcome.AlreadyPresent -> "AlreadyPresent"
            is AppendOutcome.Queued -> "Queued"
            is AppendOutcome.Deferred -> "Deferred"
            is AppendOutcome.Failed -> "Failed"
        }
        logger.info("share.append target=$id writer=$writer override=${target is CaptureTarget.NamedGraph} outcome=$name")
        return this
    }

    private suspend fun appendToActive(
        text: String,
        captureId: String?,
        writerFactory: (RepositorySet) -> GraphWriter,
    ): AppendOutcome {
        val noGraph = AppendOutcome.Failed("No active graph", CaptureResult.NoActiveGraph)
        val repoSet = graphManager.getActiveRepositorySet() ?: return noGraph
        val graphPath = graphManager.getActiveGraphInfo()?.path ?: return noGraph
        val graphId = graphManager.getActiveGraphId() ?: return noGraph

        val writer = writerFactory(repoSet)
        if (captureId != null) {
            findBlock(repoSet, captureId)?.let { return confirmFlushed(repoSet, it, graphPath, writer) }
        }
        return when (val result = CaptureWriter.writeCapture(repoSet, fileSystem, graphPath, text, captureId, writer)) {
            is CaptureResult.Saved -> AppendOutcome.Appended(result, graphId, graphPath, repoSet, writer)
            is CaptureResult.Failed -> AppendOutcome.Failed(result.message, result)
            else -> AppendOutcome.Failed(result.toString(), result)
        }
    }

    private suspend fun findBlock(repoSet: RepositorySet, captureId: String): Block? =
        repoSet.blockRepository.getBlockByUuid(BlockUuid(captureId)).first().getOrNull()

    /**
     * The DB row can outlive a failed page flush, so [AppendOutcome.AlreadyPresent] is only reported once
     * the page file has been re-flushed with the block; otherwise the caller (inbox drain) keeps the item.
     */
    private suspend fun confirmFlushed(repoSet: RepositorySet, block: Block, graphPath: String, writer: GraphWriter): AppendOutcome {
        val page = repoSet.pageRepository.getPageByUuid(block.pageUuid).first().getOrNull()
            ?: return AppendOutcome.Failed("Page for an existing capture is missing")
        val blocks = repoSet.blockRepository.getBlocksForPage(page.uuid).first().getOrNull().orEmpty()
        return writer.savePage(page, blocks, graphPath).fold(
            { AppendOutcome.Failed("Save failed: $it") },
            { AppendOutcome.AlreadyPresent },
        )
    }

    companion object {
        private val logger = Logger("JournalAppender")
        private const val WRITER_ACTIVE = "active"
        private const val WRITER_MARKDOWN = "markdown"
        const val IMAGE_NEEDS_OPEN_GRAPH_UI = "image-needs-open-graph-ui"
        const val NOT_SUPPORTED_YET = "NotSupportedYet: appending to a non-active graph needs Story 4.1.3"
    }
}
