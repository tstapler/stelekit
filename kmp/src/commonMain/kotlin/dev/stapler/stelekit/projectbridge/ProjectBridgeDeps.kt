// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

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
)
