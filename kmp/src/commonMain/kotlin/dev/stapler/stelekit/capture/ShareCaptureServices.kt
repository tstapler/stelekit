// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.RegistryGraphLocator
import dev.stapler.stelekit.merge.AssetCopier
import dev.stapler.stelekit.merge.MarkdownTargetWriter
import dev.stapler.stelekit.merge.OffGraphTarget
import dev.stapler.stelekit.merge.TargetWriterCapabilities
import dev.stapler.stelekit.merge.TargetWriterRouter
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

/**
 * The one [TargetWriterRouter] recipe, shared by share and copy: the real `ActiveTargetWriter` for the open
 * graph and a [MarkdownTargetWriter] (symlink-resolving via [canonicalize]) for any other.
 */
fun createTargetWriterRouter(
    graphManager: GraphManager,
    graphFileSystem: PlatformFileSystem,
    capabilities: TargetWriterCapabilities,
    canonicalize: (String) -> String,
    activeHooks: ActiveWriteHooks,
): TargetWriterRouter = TargetWriterRouter(
    graphManager = graphManager,
    locator = RegistryGraphLocator(graphManager.graphRegistry),
    capabilities = capabilities,
    activeWriterFor = { ready -> activeTargetWriterFor(ready, activeHooks, graphFileSystem, graphManager) },
    offGraphWriterFor = { info ->
        MarkdownTargetWriter(graphFileSystem, OffGraphTarget(info.id, info.path, isActive = false), capabilities, canonicalize)
    },
)

/**
 * Wiring only: builds the one router, the queuing share appender, and the drain (whose appender
 * does NOT queue, so a failed delivery cannot re-enqueue itself).
 *
 * The router's active-writer slot is the real `ActiveTargetWriter` (see [activeTargetWriterFor]),
 * going through [activeHooks] once the editor is composed. [graphFileSystem] is the file system graphs live on.
 */
fun createShareCaptureServices(
    graphManager: GraphManager,
    graphFileSystem: PlatformFileSystem,
    config: ShareInboxConfig,
    activeHooks: ActiveWriteHooks = ActiveWriteHooks(),
): ShareCaptureServices {
    val locator = RegistryGraphLocator(graphManager.graphRegistry)
    val router = createTargetWriterRouter(graphManager, graphFileSystem, config.capabilities, config.canonicalize, activeHooks)
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
