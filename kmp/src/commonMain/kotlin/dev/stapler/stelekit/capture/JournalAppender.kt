// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.GraphWriter
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

    /** A block with this `captureId` already exists; nothing was written. */
    data object AlreadyPresent : AppendOutcome

    /** Durably queued for later delivery. */
    data class Queued(val reason: String) : AppendOutcome

    /** [cause] keeps the specific [CaptureResult] (e.g. `NoActiveGraph`) for UI mapping. */
    data class Failed(val error: String, val cause: CaptureResult? = null) : AppendOutcome
}

/** Seam for appending to a graph that is not active; implemented by Story 4.1.3 via `TargetWriterRouter`. */
fun interface OffGraphAppendRoute {
    suspend fun append(graphId: GraphId, text: String, captureId: String?): AppendOutcome
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
        CaptureTarget.ActiveGraph -> appendToActive(text, captureId, writerFactory)
        is CaptureTarget.NamedGraph ->
            if (target.graphId == graphManager.getActiveGraphId()) {
                appendToActive(text, captureId, writerFactory)
            } else {
                offGraphRoute?.append(target.graphId, text, captureId)
                    ?: AppendOutcome.Failed(NOT_SUPPORTED_YET)
            }
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

        if (captureId != null && blockExists(repoSet, captureId)) return AppendOutcome.AlreadyPresent

        val writer = writerFactory(repoSet)
        return when (val result = CaptureWriter.writeCapture(repoSet, fileSystem, graphPath, text, captureId, writer)) {
            is CaptureResult.Saved -> AppendOutcome.Appended(result, graphId, graphPath, repoSet, writer)
            is CaptureResult.Failed -> AppendOutcome.Failed(result.message, result)
            else -> AppendOutcome.Failed(result.toString(), result)
        }
    }

    private suspend fun blockExists(repoSet: RepositorySet, captureId: String): Boolean =
        repoSet.blockRepository.getBlockByUuid(BlockUuid(captureId)).first().getOrNull() != null

    companion object {
        const val NOT_SUPPORTED_YET = "NotSupportedYet: appending to a non-active graph needs Story 4.1.3"
    }
}
