// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import arrow.core.left
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.RegistryGraphLocator
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.merge.AssetCopier
import dev.stapler.stelekit.merge.MarkdownTargetWriter
import dev.stapler.stelekit.merge.MergePage
import dev.stapler.stelekit.merge.OffGraphTarget
import dev.stapler.stelekit.merge.PageKey
import dev.stapler.stelekit.merge.TargetWriter
import dev.stapler.stelekit.merge.TargetWriterCapabilities
import dev.stapler.stelekit.merge.TargetWriterRouter
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.PlatformFileSystem
import kotlinx.coroutines.flow.map

/**
 * What a host supplies to turn on sharing into non-active graphs. Absent (null) on iOS and Web: they
 * have no app-private file system here and cannot write an inactive graph (see [TargetWriterCapabilities]).
 *
 * @param inboxFileSystem file system for the app-private inbox (never a graph file system)
 * @param inboxRoot app-private `share-inbox` directory
 * @param canonicalize symlink-resolving path function for the off-graph writer and asset copier
 */
class ShareInboxConfig(
    val inboxFileSystem: FileSystem,
    val inboxRoot: String,
    val capabilities: TargetWriterCapabilities,
    val canonicalize: (String) -> String,
)

/**
 * The share pipeline for one app run: [appender] is the share entry point (queues on refusal),
 * [drain] delivers queued shares once their graph is ready and must be started by the host.
 */
class ShareCaptureServices(
    val inbox: ShareInbox,
    val appender: JournalAppender,
    val drain: ShareInboxDrain,
    /** The one router, exposed for undo and the destination picker's availability check. */
    val router: TargetWriterRouter,
    val capabilities: TargetWriterCapabilities,
)

/** The router's ready-graph slot: always `Retryable`, so the share is queued and delivered through the open-graph path. */
private class ActiveChainDeferral(private val graphId: GraphId) : TargetWriter {
    private fun busy() = DomainError.MergeError.Retryable(
        "${graphId.value} became the open graph; retry through the open-graph path",
    ).left()

    override suspend fun readExisting(page: PageKey) = busy()
    override suspend fun write(page: PageKey, merged: MergePage) = busy()
    override suspend fun deletePageFile(page: PageKey, expectedHash: String) = busy()
    override suspend fun fileHash(page: PageKey) = busy()
    override suspend fun removeBlocks(page: PageKey, uuids: Set<String>, expectedContentHashes: Map<String, String>) = busy()
}

/**
 * Wiring only: builds the one router, the queuing share appender, and the drain (whose appender
 * does NOT queue, so a failed delivery cannot re-enqueue itself).
 *
 * The router's active-writer slot refuses with `Retryable`: a share that races a graph becoming
 * ready is queued and delivered by the drain through the open-graph path. [graphFileSystem] is the
 * file system graphs live on.
 */
fun createShareCaptureServices(
    graphManager: GraphManager,
    graphFileSystem: PlatformFileSystem,
    config: ShareInboxConfig,
): ShareCaptureServices {
    val locator = RegistryGraphLocator(graphManager.graphRegistry)
    val router = TargetWriterRouter(
        graphManager = graphManager,
        locator = locator,
        capabilities = config.capabilities,
        activeWriterFor = { ready -> ActiveChainDeferral(ready.id) },
        offGraphWriterFor = { info ->
            MarkdownTargetWriter(
                graphFileSystem,
                OffGraphTarget(info.id, info.path, isActive = false),
                config.capabilities,
                config.canonicalize,
            )
        },
    )
    val inbox = ShareInbox(config.inboxFileSystem, config.inboxRoot)
    val raw = RouterOffGraphRoute(router, locator, AssetCopier(graphFileSystem, config.canonicalize))
    val appender = JournalAppender(graphManager, graphFileSystem, InboxFallbackAppender(raw, inbox))
    val drainAppender = JournalAppender(graphManager, graphFileSystem, raw)
    val drain = ShareInboxDrain(
        inbox = inbox,
        readyGraphId = graphManager.readyGraph.map { it?.id },
        awaitPendingMigration = { graphManager.awaitPendingMigration() },
        currentReadyId = { graphManager.readyGraphId },
        appender = JournalInboxAppender(drainAppender),
        registeredGraphs = graphManager.graphRegistry.map { registry -> registry.graphs.map { it.id } },
    )
    return ShareCaptureServices(inbox, appender, drain, router, config.capabilities)
}
