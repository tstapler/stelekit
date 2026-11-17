// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.sketchimport.PngCalibrationRecord
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.jobs.JobTracker
import dev.stapler.stelekit.merge.TargetWriterRouter
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.sketch.v2.runtime.session.ClassicNoticeLedger
import dev.stapler.stelekit.sketch.v2.runtime.session.ProjectAckStore
import dev.stapler.stelekit.sketch.v2.runtime.session.SheetLeaseRegistry
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.QualitySignals
import dev.stapler.stelekit.platform.BrowserPersistence
import dev.stapler.stelekit.platform.UnsupportedBrowserPersistence
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
    /** The graph's one [SheetLeaseRegistry], shared by the legacy editor, the v2 editor, the preview path and the importer. */
    val sheetLeases: SheetLeaseRegistry,
    /** The synced-v2 discovery scan (Task 1.4.7n); non-null only where the verdict is Available and the classic file side is Off. */
    val syncedV2Discovery: () -> SyncedV2ProjectDiscovery? = { null },
    /** Waits until a saved sheet reaches storage; only the web root replaces the immediate default. */
    val sketchDurability: SketchDurability = ImmediateSketchDurability,
    /** The hidden-page flush request open savers collect; only the web root replaces the silent default. */
    val pageLifecycle: PageLifecycle = NoPageLifecycle,
    /** The device-local consent records of the migration UI (Task 6.2.0c); built once per root, null where no graph state exists. */
    val acks: ProjectAckStore? = null,
    val classicNotices: ClassicNoticeLedger? = null,
    val quality: QualitySignals? = null,
    val browserPersistence: BrowserPersistence = UnsupportedBrowserPersistence,
    /** Whether a warning was confirmed in Settings this session (the first-write dialog then shows it by reference). */
    val sessionConfirmation: SessionConfirmation = SessionConfirmation(),
    /** Tells an open sketch editor that Settings changed the mode (UX-69). */
    val engineChange: EngineChangeBus = EngineChangeBus(),
    /** The platform root's palm, cancel, edge-exclusion and raw-pointer seams of the v2 canvas (Task 3.1.2h). */
    val sketchInput: dev.stapler.stelekit.platform.SketchPlatformInput = dev.stapler.stelekit.platform.SketchPlatformInput.None,
) {
    /** The `GraphManager.onGraphRemoved` cleanup of the per-graph consent records; a root composes it into its one handler slot. */
    fun onGraphRemoved(graphId: String) {
        acks?.removeGraph(graphId)
        classicNotices?.removeGraph(graphId)
    }
}
