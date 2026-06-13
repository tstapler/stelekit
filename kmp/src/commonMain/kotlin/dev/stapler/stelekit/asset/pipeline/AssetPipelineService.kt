package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.repository.AssetRepository
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

    suspend fun processAsset(asset: AssetEntry, writeActor: DatabaseWriteActor?) {
        val alreadyAttempted = attemptedMutex.withLock { !attemptedUuids.add(asset.uuid.value) }
        if (alreadyAttempted) return

        val plugins = registry.all.filter { it.canProcess(asset) }
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
            if (writeActor != null) {
                writeActor.execute { assetRepository.markMlFailed(asset.uuid, now) }
            } else {
                assetRepository.markMlFailed(asset.uuid, now)
            }
        } else {
            val finalLabels = allLabels.distinct()
            if (writeActor != null) {
                writeActor.execute {
                    assetRepository.updateAutoLabels(asset.uuid, finalLabels, "LOCAL")
                    assetRepository.updateOcrText(asset.uuid, ocrText)
                    assetRepository.markMlProcessed(asset.uuid, now)
                }
            } else {
                assetRepository.updateAutoLabels(asset.uuid, finalLabels, "LOCAL")
                assetRepository.updateOcrText(asset.uuid, ocrText)
                assetRepository.markMlProcessed(asset.uuid, now)
            }
        }
    }

    fun scheduleProcess(asset: AssetEntry, writeActor: DatabaseWriteActor?) {
        scope.launch {
            try {
                processAsset(asset, writeActor)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error("scheduleProcess failed for ${asset.uuid.value}: ${e.message}")
            }
        }
    }

    fun scheduleBackfill(writeActor: DatabaseWriteActor?) {
        backfillJob?.cancel()
        backfillJob = scope.launch {
            try {
                drainUnprocessed(writeActor)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error("Backfill drain failed: ${e.message}")
            }
        }
    }

    private suspend fun drainUnprocessed(writeActor: DatabaseWriteActor?) {
        val batchSize = 10
        var offset = 0
        while (true) {
            val batch = assetRepository.getUnprocessedAssets(limit = batchSize, offset = offset)
                .first()
                .getOrNull() ?: break
            if (batch.isEmpty()) break
            val filtered = attemptedMutex.withLock { batch.filter { it.uuid.value !in attemptedUuids } }
            for (asset in filtered) {
                processAsset(asset, writeActor)
            }
            yield()
            if (batch.size < batchSize) break
            offset += batchSize
        }
    }

    fun shutdown() { scope.cancel() }
}
