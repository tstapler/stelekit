package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.repository.AssetRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

class AssetPipelineService(
    private val registry: PluginRegistry,
    private val assetRepository: AssetRepository,
    private val writeActor: DatabaseWriteActor,
    /** Re-derives the plugin list at each drain start (see [defaultAssetPlugins]); null keeps [registry] as is. */
    private val pluginSource: (() -> List<AssetPipelinePlugin>)? = null,
) {
    private val logger = Logger("AssetPipelineService")
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                logger.error("AssetPipelineService uncaught: ${throwable.message}")
            }
    )
    private val attemptedMutex = Mutex()
    private val attemptedUuids = mutableSetOf<String>()
    private var backfillJob: Job? = null

    suspend fun processAsset(asset: AssetEntry) {
        // No enabled plugin handles this asset: leave it unprocessed so enabling one later still picks it up.
        val plugins = registry.all.filter { it.canProcess(asset) }
        if (plugins.isEmpty()) return

        val alreadyAttempted = attemptedMutex.withLock { !attemptedUuids.add(asset.uuid.value) }
        if (alreadyAttempted) return

        val allLabels = mutableListOf<String>()
        var ocrText: String? = null
        var failed = false

        for (plugin in plugins) {
            try {
                val result = plugin.processAsset(asset)
                result.fold(
                    ifLeft = {
                        logger.error("Plugin ${plugin.id} failed on ${asset.uuid.value}: ${it.message}")
                        failed = true
                    },
                    ifRight = { pluginResult ->
                        when (pluginResult) {
                            is AssetPipelineResult.Labels -> allLabels.addAll(pluginResult.labels)
                            is AssetPipelineResult.OcrText -> ocrText = pluginResult.text
                            is AssetPipelineResult.CloudDescription -> allLabels.addAll(pluginResult.labelsAdded)
                            is AssetPipelineResult.Combined -> {
                                allLabels.addAll(pluginResult.labels)
                                if (pluginResult.ocrText != null) ocrText = pluginResult.ocrText
                            }
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error("Plugin ${plugin.id} threw on ${asset.uuid.value}: ${e.message}")
                failed = true
            }
        }

        val now = Clock.System.now().toEpochMilliseconds()
        if (failed) {
            writeActor.execute {
                @OptIn(DirectRepositoryWrite::class)
                assetRepository.markMlFailed(asset.uuid, now)
            }
        } else {
            val finalLabels = allLabels.distinct()
            writeActor.execute {
                @OptIn(DirectRepositoryWrite::class)
                assetRepository.updateAutoLabels(asset.uuid, finalLabels, "LOCAL")
                @OptIn(DirectRepositoryWrite::class)
                assetRepository.updateOcrText(asset.uuid, ocrText)
                @OptIn(DirectRepositoryWrite::class)
                assetRepository.markMlProcessed(asset.uuid, now)
            }
        }
    }

    fun scheduleProcess(asset: AssetEntry) {
        scope.launch {
            try {
                processAsset(asset)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error("scheduleProcess failed for ${asset.uuid.value}: ${e.message}")
            }
        }
    }

    /** Starts a drain; with no plugins enabled nothing starts, so no asset is marked processed or failed. */
    fun scheduleBackfill() {
        pluginSource?.let { registry.replaceAll(it()) }
        if (registry.all.isEmpty()) {
            logger.info("No asset plugins enabled; not starting a drain")
            return
        }
        backfillJob?.cancel()
        backfillJob = scope.launch {
            try {
                drainUnprocessed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error("Backfill drain failed: ${e.message}")
            }
        }
    }

    private suspend fun drainUnprocessed() {
        val batchSize = 10
        var offset = 0
        while (true) {
            val batch = assetRepository.getUnprocessedAssets(limit = batchSize, offset = offset)
                .first()
                .getOrNull() ?: break
            if (batch.isEmpty()) break
            val filtered = attemptedMutex.withLock { batch.filter { it.uuid.value !in attemptedUuids } }
            for (asset in filtered) {
                processAsset(asset)
            }
            yield()
            if (batch.size < batchSize) break
            offset += batchSize
        }
    }

    /** Cancels a drain in flight; assets already written stay. */
    fun cancelBackfill() {
        backfillJob?.cancel()
        backfillJob = null
    }

    fun shutdown() { scope.cancel() }
}
