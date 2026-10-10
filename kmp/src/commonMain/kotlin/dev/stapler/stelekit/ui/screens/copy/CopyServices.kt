// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.copy

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.merge.AssetCopier
import dev.stapler.stelekit.merge.CopyRunHost
import dev.stapler.stelekit.merge.MergeManifestStore
import dev.stapler.stelekit.merge.MergePlan
import dev.stapler.stelekit.merge.MergeProgress
import dev.stapler.stelekit.merge.MergeUndo
import dev.stapler.stelekit.merge.PageMergeService
import dev.stapler.stelekit.merge.PageSource
import dev.stapler.stelekit.merge.PlanRequest
import dev.stapler.stelekit.merge.RoutedTargetWriter
import dev.stapler.stelekit.merge.SourcePlatform
import dev.stapler.stelekit.merge.SourceReadCapabilities
import dev.stapler.stelekit.merge.TargetWriterCapabilities
import dev.stapler.stelekit.merge.TargetWriterRouter
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock

/** What a host supplies to turn the copy feature on. Absent (null) on platforms with no app-data directory (iOS/Web). */
class CopyHostConfig(
    val appDataDir: String,
    val fileSystem: okio.FileSystem,
    val runHost: CopyRunHost,
    val sourcePlatform: SourcePlatform,
    val canonicalize: (String) -> String = { it },
)

/** The copy pipeline for one app run; [router] is the same instance share uses when share is enabled. */
class CopyServices(
    val service: PageMergeService,
    val runHost: CopyRunHost,
    val router: TargetWriterRouter,
    val manifests: MergeManifestStore,
    val undo: MergeUndo,
    val probe: DestinationProbe,
    val destinationSettings: CopyDestinationSettings,
    val appDataDir: String,
    val fileSystem: okio.FileSystem,
)

/**
 * Wiring only. [router] is passed in (never built here) so merge and share cannot grow separate lock logic.
 * [writerCapabilities] must be the capabilities [router] was built with.
 */
fun createCopyServices(
    graphManager: GraphManager,
    graphFileSystem: PlatformFileSystem,
    router: TargetWriterRouter,
    writerCapabilities: TargetWriterCapabilities,
    config: CopyHostConfig,
    settings: Settings,
): CopyServices {
    val manifests = MergeManifestStore(config.fileSystem, config.appDataDir)
    // Retained by the host so a controller rebuilt on Activity recreation keeps the in-flight apply and retry state.
    val service = config.runHost.retain(graphManager) { newService(graphManager, graphFileSystem, router, config) }
    service.rebindRouter(router)
    return CopyServices(
        service = service,
        runHost = config.runHost,
        router = router,
        manifests = manifests,
        undo = MergeUndo(
            manifests = manifests,
            writerFor = { target -> RoutedTargetWriter(router, target) },
            nowEpochMs = { Clock.System.now().toEpochMilliseconds() },
        ),
        probe = CapabilityDestinationProbe(writerCapabilities, SourceReadCapabilities(config.sourcePlatform)),
        destinationSettings = CopyDestinationSettings(settings),
        appDataDir = config.appDataDir,
        fileSystem = config.fileSystem,
    )
}

private fun newService(
    graphManager: GraphManager,
    graphFileSystem: PlatformFileSystem,
    router: TargetWriterRouter,
    config: CopyHostConfig,
): PageMergeService =
    PageMergeService(
        router = router,
        fileSystem = config.fileSystem,
        appDataDir = config.appDataDir,
        // Link closure is off in v1; if enabled it reads the open graph, which is the source in Push.
        closureLookup = { names ->
            graphManager.activeRepositorySet.value?.pageRepository?.getPagesByNames(names)
                ?: emptyList<Page>().right()
        },
        assetCopier = AssetCopier(graphFileSystem, config.canonicalize),
        graphRoot = { id -> graphManager.graphRegistry.value.graphs.firstOrNull { it.id == id }?.path },
    )

/** [CopyFlowGateway] over [PageMergeService] and the [PageSource] being browsed (the open graph in Push). */
class PageMergeServiceGateway(
    private val service: PageMergeService,
    private val source: PageSource,
) : CopyFlowGateway {
    override val progress: StateFlow<MergeProgress> = service.progress

    override suspend fun plan(request: PlanRequest): Either<DomainError, MergePlan> = service.plan(request, source)

    override suspend fun apply(plan: MergePlan) = service.apply(plan)

    override fun cancel() = service.cancel()
}
