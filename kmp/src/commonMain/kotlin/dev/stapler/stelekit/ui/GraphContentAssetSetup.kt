// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetIndexProgress
import dev.stapler.stelekit.asset.AssetIndexService
import dev.stapler.stelekit.asset.AssetIndexingController
import dev.stapler.stelekit.asset.AssetMoveService
import dev.stapler.stelekit.asset.IndexingMediaAttachmentService
import dev.stapler.stelekit.asset.pipeline.AssetPipelineService
import dev.stapler.stelekit.asset.pipeline.AssetPluginsController
import dev.stapler.stelekit.asset.pipeline.CloudConsentGate
import dev.stapler.stelekit.asset.pipeline.CloudPluginDeps
import dev.stapler.stelekit.asset.pipeline.CloudSettingsController
import dev.stapler.stelekit.asset.pipeline.EgressLedger
import dev.stapler.stelekit.asset.pipeline.cloudNetwork
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.llm.AnthropicMessagesClient
import dev.stapler.stelekit.llm.LlmCredentialStore
import dev.stapler.stelekit.platform.security.CredentialStore
import dev.stapler.stelekit.asset.pipeline.PauseReason
import dev.stapler.stelekit.asset.pipeline.PipelineRunMarker
import dev.stapler.stelekit.asset.pipeline.PluginRegistry
import dev.stapler.stelekit.asset.pipeline.defaultAssetPlugins
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Moves [AssetEntry] into an `assets/` subfolder; see [AssetMoveService]. */
typealias AssetMover = suspend (AssetEntry, String) -> Either<DomainError, Unit>

/**
 * The per-graph asset stack: the indexing switch/controller, the move entry point, and the
 * attachment service that registers new files with the index.
 *
 * [moveAsset] is null when this build hides the asset switch (release variants before the heap
 * gate), so the Move action is not offered there.
 */
/** Test seams for the cloud plugin's key store and HTTP engine; production leaves both null. */
class CloudStackSeams(
    val credentials: LlmCredentialStore? = null,
    val httpClient: io.ktor.client.HttpClient? = null,
    val network: (() -> dev.stapler.stelekit.asset.pipeline.CloudNetwork)? = null,
)

class GraphContentAssetStack(
    val indexing: AssetIndexingController,
    val plugins: AssetPluginsController,
    val moveAsset: AssetMover?,
    val attachmentService: dev.stapler.stelekit.service.MediaAttachmentService?,
)

/**
 * Constructs [AssetIndexService] and [AssetMoveService] for the active graph. Once the graph
 * reports fully loaded (so `loadGraph` never overlaps the walk) it starts the index backfill if
 * the `asset_pipeline_enabled` switch is on, and replays any move left half-done by a crash.
 * Leaving composition (graph switch) cancels the run and releases the service's scope.
 */
@Composable
internal fun rememberGraphContentAssetStack(
    deps: GraphContentDeps,
    effectiveFileSystem: FileSystem,
    graphIoStack: GraphContentGraphIoStack,
    appState: StateFlow<AppState>,
    memoryPressure: MemoryPressureListeners,
    logger: Logger,
    cloudSeams: CloudStackSeams = CloudStackSeams(),
): GraphContentAssetStack {
    val repos = deps.repos
    val delegateAttachments = deps.platformIntegrations.attachmentService
    // One marker for the whole pipeline (index walk + plugin drain): the crash-loop breaker counts runs, not parts.
    val runMarker = remember(deps.platformSettings) { PipelineRunMarker(deps.platformSettings) }
    val indexing = remember(repos, deps.platformSettings, runMarker) {
        AssetIndexingController(deps.platformSettings, AssetIndexService(repos.assetRepository), runMarker = runMarker)
    }
    val moveService = remember(repos) { AssetMoveService(repos.assetRepository, repos.restrictedQueries) }
    DisposableEffect(indexing) { onDispose { indexing.close() } }
    // Plugins are re-derived from the per-plugin settings keys at each drain start; with none set
    // the pipeline never starts and only indexing runs.
    val caps = deps.platformIntegrations.platformCaps
    val llmCredentials = remember(cloudSeams) { cloudSeams.credentials ?: LlmCredentialStore(CredentialStore()) }
    val cloudGate = remember(deps.platformSettings, llmCredentials) { CloudConsentGate(deps.platformSettings, llmCredentials) }
    val cloudClient = remember(llmCredentials, cloudSeams) {
        val apiKey = { llmCredentials.getApiKey(CloudConsentGate.PROVIDER) }
        cloudSeams.httpClient?.let { AnthropicMessagesClient(it, apiKey) } ?: AnthropicMessagesClient.withDefaults(apiKey)
    }
    val networkMonitor = remember { NetworkMonitor() }
    DisposableEffect(cloudClient) { onDispose { cloudClient.close() } }
    val activeGraphId = { deps.graphManager.graphRegistry.value.activeGraphId?.value.orEmpty() }
    val pluginRegistry = remember(deps.platformSettings, caps, cloudGate) {
        PluginRegistry(deps.platformSettings, caps, cloudConsentGranted = { cloudGate.token(activeGraphId()) != null })
    }
    val pipeline = remember(repos, deps.platformSettings, caps, effectiveFileSystem, runMarker, pluginRegistry, cloudGate, cloudClient) {
        repos.writeActor?.let { actor ->
            AssetPipelineService(
                pluginRegistry, repos.assetRepository, actor,
                pluginSource = {
                    defaultAssetPlugins(
                        caps, deps.platformSettings, effectiveFileSystem,
                        cloud = CloudPluginDeps(cloudClient, cloudGate, activeGraphId, network = cloudSeams.network ?: { networkMonitor.cloudNetwork() }),
                    )
                },
                runMarker = runMarker,
            )
        }
    }
    DisposableEffect(pipeline) { onDispose { pipeline?.shutdown() } }
    val cloudSettings = remember(cloudGate, pluginRegistry, repos, pipeline, networkMonitor) {
        CloudSettingsController(
            gate = cloudGate,
            registry = pluginRegistry,
            ledger = EgressLedger(deps.platformSettings),
            assets = repos.assetRepository,
            graphId = activeGraphId,
            graphName = {
                val registry = deps.graphManager.graphRegistry.value
                registry.graphs.firstOrNull { it.id == registry.activeGraphId }?.displayName.orEmpty()
            },
            meteredAware = { networkMonitor.isMetered != null },
            now = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
            onEnabled = { pipeline?.scheduleBackfill() },
            writeActor = repos.writeActor,
        )
    }
    val plugins = remember(pluginRegistry, pipeline, indexing, cloudSettings) {
        AssetPluginsController(pluginRegistry, pipeline, indexing, deps.platformSettings, caps.power, cloud = cloudSettings)
    }
    LaunchedEffect(indexing, pluginRegistry) {
        indexing.enabled.collect { pluginRegistry.setMasterEnabled(it) }
    }
    DisposableEffect(indexing, pipeline, memoryPressure) {
        val unregister = memoryPressure.register {
            indexing.onMemoryPressure()
            pipeline?.pause(PauseReason.Memory)
        }
        onDispose { unregister() }
    }
    DisposableEffect(plugins) { onDispose { plugins.close() } }
    LaunchedEffect(indexing, plugins) {
        indexing.progress.collect { progress ->
            if (progress is AssetIndexProgress.Finished) plugins.requestBackfill()
        }
    }

    LaunchedEffect(appState, indexing, moveService, effectiveFileSystem, graphIoStack) {
        appState.map { it.isFullyLoaded to it.currentNotesPath }.distinctUntilChanged().collectLatest { (loaded, notes) ->
            if (!loaded || notes == null) {
                indexing.onGraphNotReady()
                return@collectLatest
            }
            indexing.onGraphReady(notes, repos.writeActor, effectiveFileSystem)
            replayPendingMoves(moveService, notes, effectiveFileSystem, graphIoStack, repos.writeActor, logger)
        }
    }

    return remember(indexing, plugins, moveService, delegateAttachments, effectiveFileSystem, graphIoStack, appState) {
        val mover: AssetMover? = if (indexing.toggleVisible) {
            { asset, subfolder ->
                val notes = appState.value.currentNotesPath
                if (notes == null) {
                    DomainError.DatabaseError.WriteFailed("No active graph").left()
                } else {
                    moveService.moveAsset(asset, subfolder, notes, effectiveFileSystem, graphIoStack.graphWriter, repos.writeActor)
                }
            }
        } else {
            null
        }
        GraphContentAssetStack(
            indexing = indexing,
            plugins = plugins,
            moveAsset = mover,
            attachmentService = delegateAttachments?.let { IndexingMediaAttachmentService(it, indexing) },
        )
    }
}

/**
 * Not gated by the indexing switch: a crash mid-move must heal even if indexing was turned off
 * since. Reads only the (tiny) WAL table.
 */
private suspend fun replayPendingMoves(
    moveService: AssetMoveService,
    notes: dev.stapler.stelekit.model.EffectiveNotesPath,
    fileSystem: FileSystem,
    graphIoStack: GraphContentGraphIoStack,
    writeActor: dev.stapler.stelekit.db.DatabaseWriteActor?,
    logger: Logger,
) {
    try {
        moveService.replayPendingMoves(notes, fileSystem, graphIoStack.graphWriter, writeActor)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn("Pending asset move replay failed: ${e.message}")
    }
}
