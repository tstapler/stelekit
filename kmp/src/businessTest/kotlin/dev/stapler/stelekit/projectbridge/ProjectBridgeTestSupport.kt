// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.RegistryGraphLocator
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.jobs.JobTracker
import dev.stapler.stelekit.merge.TargetWriterCapabilities
import dev.stapler.stelekit.merge.TargetWriterRouter
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class BridgeMapSettings : Settings {
    private val store = mutableMapOf<String, String>()
    override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
    override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
    override fun putString(key: String, value: String) { store[key] = value }
    override fun containsKey(key: String) = store.containsKey(key)
}

/** A real router over an in-memory graph manager; the job only checks that one exists, so no writer is built. */
internal fun newBridgeTestRouter(): TargetWriterRouter {
    val manager = GraphManager(
        BridgeMapSettings().also { it.putString("graph_registry", Json.encodeToString(GraphRegistry())) },
        DriverFactory(), StubFileSystem(), defaultBackend = GraphBackend.IN_MEMORY,
    )
    return TargetWriterRouter(
        manager, RegistryGraphLocator(manager.graphRegistry), TargetWriterCapabilities(platformSupportsOffGraphWrite = false),
        activeWriterFor = { error("not used") }, offGraphWriterFor = { error("not used") },
    )
}

/** Counts collection passes; optionally parks the first one until [gate] is completed. */
internal class CountingCollector(
    private val bound: List<BoundPageRef> = emptyList(),
    private val gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
) : ProjectSourceCollector() {
    var calls = 0
        private set

    override suspend fun collect(): CollectedSources {
        calls++
        if (calls == 1) gate?.await()
        return CollectedSources(bound)
    }
}

internal fun bridgeDeps(
    settings: Settings,
    jobTracker: JobTracker,
    router: () -> TargetWriterRouter? = { null },
    isGitSyncBusy: () -> Boolean = { false },
    readyGraphIds: Flow<String?> = MutableStateFlow(BRIDGE_GRAPH),
    hasConfiguredProject: Boolean = true,
    probe: PageSettleProbe = PageSettleProbe { PageSettleState.Settled },
    sketchRepositories: dev.stapler.stelekit.repository.SketchRepositories? = null,
    sidecarStore: ProjectSidecarStore? = null,
    boundSources: suspend () -> SourceReadResult = { SourceReadResult.EMPTY },
    yamlSnapshot: suspend () -> String? = { null },
    fusionSetup: suspend () -> ProjectFusionSetup? = { null },
) = ProjectBridgeDeps(
    routerProvider = router,
    sketchRepositories = { sketchRepositories },
    sidecarStore = { sidecarStore },
    fileSystem = StubFileSystem(),
    isGitSyncBusy = isGitSyncBusy,
    settings = ProjectBridgeSettings(settings),
    jobTracker = jobTracker,
    readyGraphIds = readyGraphIds,
    hasConfiguredProject = { hasConfiguredProject },
    pageSettleProbe = probe,
    boundSources = boundSources,
    yamlSnapshot = yamlSnapshot,
    fusionSetup = fusionSetup,
)

internal const val BRIDGE_GRAPH = "graph-a"

/** Flag and per-graph digest opt-in both on for [graphId]. */
internal fun enableBridge(settings: Settings, graphId: String = BRIDGE_GRAPH) {
    ProjectBridgeSettings(settings).apply {
        homeSketchEnabled = true
        setDigestEnabled(graphId, true)
    }
}
