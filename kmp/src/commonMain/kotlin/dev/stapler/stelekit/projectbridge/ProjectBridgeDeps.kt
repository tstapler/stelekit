// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.sketchimport.PngCalibrationRecord
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.jobs.JobTracker
import dev.stapler.stelekit.merge.TargetWriterRouter
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.SketchRepositories
import kotlinx.coroutines.flow.Flow

/**
 * Everything the post-sync project job needs, as injected providers. None of these is a
 * Composable-lifetime object: a provider is re-read at run time, so a router published or
 * cleared by `DisposableEffect` is always seen as it is now.
 */
class ProjectBridgeDeps(
    /** The one app router (published by `App.kt` through `TargetWriterRouterHolder`); null when absent on this platform. */
    val routerProvider: () -> TargetWriterRouter?,
    /** The graph's sketch repositories (wired by `RepositoryFactory`); null where no graph is open. */
    val sketchRepositories: () -> SketchRepositories?,
    val sidecarStore: () -> ProjectSidecarStore?,
    val fileSystem: FileSystem,
    val isGitSyncBusy: () -> Boolean,
    val settings: ProjectBridgeSettings,
    val jobTracker: JobTracker,
    /** `GraphManager.readyGraph` mapped to its id: the readiness pair, not the registry's `activeGraphId`. */
    val readyGraphIds: Flow<String?>,
    /** True when a sketch project is configured for the graph; false until projects exist (Story 2.3). */
    val hasConfiguredProject: suspend (graphId: String) -> Boolean,
    val pageSettleProbe: PageSettleProbe,
    /** The graph's write actor; sidecar pulls write the sketch cache rows through it. */
    val writeActor: () -> DatabaseWriteActor? = { null },
    /** The bound pages' text for the graph's project (Story 5.9b); empty when nothing is bound. */
    val boundSources: suspend () -> SourceReadResult = { SourceReadResult.EMPTY },
    /** The YAML snapshot text recorded for the graph's project (Story 5.9c); null when none was picked. */
    val yamlSnapshot: suspend () -> String? = { null },
    /** The attached SVG drawings' text for the graph's project (Story 5.9d); empty when none is attached. */
    val attachedSheets: suspend () -> List<SheetSvg> = { emptyList() },
    /** The project's registry and fusion spec (synced in its settings); null until the project has a registry, so nothing fuses and nothing is written. */
    val fusionSetup: suspend () -> ProjectFusionSetup? = { null },
    /** The PNG sheets' calibration records for the graph's project (Story 4.5b); empty when none is tagged. */
    val pngTags: suspend () -> List<PngCalibrationRecord> = { emptyList() },
)
