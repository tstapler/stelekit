package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.repository.AssetRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Runs the enabled [AssetPipelinePlugin]s over unprocessed assets.
 *
 * One asset is in flight at a time ([inFlight]) and plugin work runs off the DB dispatcher. The
 * drain reaches a terminal state (processed or failed) for every asset a plugin handles; an asset
 * no enabled plugin handles stays unprocessed so enabling a plugin later still picks it up. All
 * writes go through [writeActor]. A plugin that throws [PLUGIN_FAILURE_THRESHOLD] times in a row
 * (a systemic fault such as OutOfMemoryError, not an ordinary per-asset `Left`) is skipped for the
 * rest of the run and its assets stay unprocessed for the next run.
 */
class AssetPipelineService(
    private val registry: PluginRegistry,
    private val assetRepository: AssetRepository,
    private val writeActor: DatabaseWriteActor,
    /** Re-derives the plugin list at each drain start (see [defaultAssetPlugins]); null keeps [registry] as is. */
    private val pluginSource: (() -> List<AssetPipelinePlugin>)? = null,
    /** Persisted "a run is in progress" marker for the crash-loop breaker; null disables it. */
    private val runMarker: PipelineRunMarker? = null,
) {
    private val logger = Logger("AssetPipelineService")
    private val scope = CoroutineScope(
        SupervisorJob() + PlatformDispatcher.Default +
            CoroutineExceptionHandler { _, throwable ->
                logger.error("AssetPipelineService uncaught: ${throwable.message}")
            }
    )
    private val attemptedMutex = Mutex()
    private val attemptedUuids = mutableSetOf<String>()
    private val inFlight = Semaphore(1)
    private val consecutiveFailures = mutableMapOf<String, Int>()
    private var backfillJob: Job? = null

    /** Set when a plugin hit a cap during the current drain; the drain stops after that asset. */
    @kotlin.concurrent.Volatile
    private var capPause: PauseReason? = null

    private val _progress = MutableStateFlow<PipelineProgress>(PipelineProgress.Idle)
    val progress: StateFlow<PipelineProgress> = _progress.asStateFlow()

    private val _initFailures = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Ids of plugins that could not start at all (a missing native library), so the UI can switch them off. */
    val pluginInitFailures: SharedFlow<String> = _initFailures.asSharedFlow()

    /** What [processAsset] did with one asset. */
    enum class Outcome { Processed, Failed, Stayed }

    suspend fun processAsset(asset: AssetEntry): Outcome = inFlight.withPermit {
        // A plugin whose breaker opened this run is skipped; an asset nobody handles stays unprocessed.
        val plugins = registry.all.filter { it.canProcess(asset) && isClosed(it.id) }
        if (plugins.isEmpty()) return@withPermit Outcome.Stayed

        val alreadyAttempted = attemptedMutex.withLock { !attemptedUuids.add(asset.uuid.value) }
        if (alreadyAttempted) return@withPermit Outcome.Stayed

        val allLabels = mutableListOf<String>()
        var ocrText: String? = null
        var cloudDescription: String? = null
        var localRan = false
        var gotResult = false
        var failed = false

        for (plugin in plugins) {
            try {
                val result = withContext(PlatformDispatcher.Default) { plugin.processAsset(asset) }
                result.fold(
                    ifLeft = {
                        logger.error("Plugin ${plugin.id} failed on ${asset.uuid.value}: ${it.message}")
                        failed = true
                        consecutiveFailures[plugin.id] = 0
                    },
                    ifRight = { pluginResult ->
                        consecutiveFailures[plugin.id] = 0
                        when (pluginResult) {
                            is AssetPipelineResult.Skipped -> {
                                if (pluginResult.pause != null) capPause = pluginResult.pause
                            }
                            is AssetPipelineResult.Labels -> { gotResult = true; localRan = true; allLabels.addAll(pluginResult.labels) }
                            is AssetPipelineResult.OcrText -> { gotResult = true; localRan = true; ocrText = pluginResult.text }
                            is AssetPipelineResult.CloudDescription -> {
                                gotResult = true
                                cloudDescription = pluginResult.description
                                allLabels.addAll(pluginResult.labelsAdded)
                            }
                            is AssetPipelineResult.Combined -> {
                                gotResult = true
                                localRan = true
                                allLabels.addAll(pluginResult.labels)
                                if (pluginResult.ocrText != null) ocrText = pluginResult.ocrText
                                if (pluginResult.cloudDescription != null) cloudDescription = pluginResult.cloudDescription
                            }
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error("Plugin ${plugin.id} threw on ${asset.uuid.value}: ${e.message}")
                if (e.isNativeLinkFailure()) _initFailures.tryEmit(plugin.id)
                failed = true
                recordFailure(plugin.id)
            }
        }

        // Every plugin that took the asset declined it (no consent, a cap): nothing ran, so it stays unprocessed.
        if (!failed && !gotResult) return@withPermit Outcome.Stayed

        val now = Clock.System.now().toEpochMilliseconds()
        if (!failed) {
            val finalLabels = allLabels.distinct()
            val storedOcr = ocrText?.let { text ->
                truncateUtf8(text).also {
                    if (it.length < text.length) logger.warn("ocr text truncated to $MAX_OCR_TEXT_BYTES bytes for ${asset.uuid.value}")
                }
            }
            val saved = writeGuarded {
                @OptIn(DirectRepositoryWrite::class)
                writeLocalResults(asset, localRan, finalLabels, storedOcr)
                    .flatMap { writeCloudDescription(asset, cloudDescription) }
                    .flatMap { assetRepository.markMlProcessed(asset.uuid, now) }
            }
            if (saved) return@withPermit Outcome.Processed
            logger.error("Writing results for ${asset.uuid.value} failed; marking it failed")
        }
        val marked = writeGuarded {
            @OptIn(DirectRepositoryWrite::class)
            assetRepository.markMlFailed(asset.uuid, now)
        }
        if (marked) Outcome.Failed else Outcome.Stayed
    }

    // A cloud-only result must not clear labels or OCR text another plugin stored earlier.
    @OptIn(DirectRepositoryWrite::class)
    private suspend fun writeLocalResults(
        asset: AssetEntry,
        localRan: Boolean,
        labels: List<String>,
        ocr: String?,
    ): Either<DomainError, Unit> {
        if (!localRan) return Either.Right(Unit)
        return assetRepository.updateAutoLabels(asset.uuid, labels, "LOCAL")
            .flatMap { assetRepository.updateOcrText(asset.uuid, ocr) }
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun writeCloudDescription(asset: AssetEntry, description: String?): Either<DomainError, Unit> =
        if (description == null) Either.Right(Unit)
        else assetRepository.updateCloudDescription(asset.uuid, description, CloudClaudePlugin.CLOUD_TAGS_SOURCE)

    private fun isClosed(pluginId: String): Boolean =
        (consecutiveFailures[pluginId] ?: 0) < PLUGIN_FAILURE_THRESHOLD

    private fun recordFailure(pluginId: String) {
        val count = (consecutiveFailures[pluginId] ?: 0) + 1
        consecutiveFailures[pluginId] = count
        if (count == PLUGIN_FAILURE_THRESHOLD) logger.warn("Plugin $pluginId failed $count times in a row; skipping it for this run")
    }

    /**
     * Runs [op] inside the write actor. A Throwable (including OutOfMemoryError) thrown by the
     * write is turned into a Left inside the op, so it never reaches the actor's loop.
     */
    private suspend fun writeGuarded(op: suspend () -> Either<DomainError, Unit>): Boolean = try {
        writeActor.execute {
            try {
                op()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                DomainError.DatabaseError.WriteFailed(e.message ?: e::class.simpleName ?: "unknown").left()
            }
        }.isRight()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.error("Pipeline write failed: ${e.message}")
        false
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
        val previous = backfillJob
        backfillJob = scope.launch {
            previous?.cancelAndJoin()
            try {
                drainUnprocessed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error("Backfill drain failed: ${e.message}")
            } finally {
                runMarker?.end(MARKER_OWNER)
            }
        }
    }

    /** Clears every failed flag, then drains again; plugins that stay broken fail the rows once more. */
    fun retryFailed() {
        scope.launch {
            val cleared = writeGuarded {
                @OptIn(DirectRepositoryWrite::class)
                assetRepository.resetFailedAssets()
            }
            if (cleared) scheduleBackfill()
        }
    }

    /** Stops a drain in flight for [reason]; assets already written stay. [resume] restarts it. */
    fun pause(reason: PauseReason) {
        val job = backfillJob ?: return
        job.cancel()
        backfillJob = null
        runMarker?.end(MARKER_OWNER)
        _progress.value = PipelineProgress.Paused(reason)
    }

    /** Restarts a paused drain. */
    fun resume() {
        if (_progress.value is PipelineProgress.Paused) scheduleBackfill()
    }

    /** Stops a drain in flight and returns to idle. */
    fun cancel() {
        backfillJob?.cancel()
        backfillJob = null
        runMarker?.end(MARKER_OWNER)
        _progress.value = PipelineProgress.Idle
    }

    /**
     * Reads the first unprocessed rows each pass (offset 0 moves forward by itself as rows leave
     * the result set). The offset only advances past rows that remain unprocessed after the
     * batch, so every row reaches a terminal state or is skipped exactly once and the loop ends.
     */
    private suspend fun drainUnprocessed() {
        attemptedMutex.withLock { attemptedUuids.clear() }
        consecutiveFailures.clear()
        capPause = null
        runMarker?.begin(MARKER_OWNER)
        val total = (assetRepository.countUnprocessedAssets().getOrNull() ?: 0L).toInt()
        var done = 0
        var failed = 0
        _progress.value = PipelineProgress.Running(done, total, failed)

        var offset = 0
        while (true) {
            val batch = assetRepository.getUnprocessedAssets(limit = BATCH_SIZE, offset = offset)
                .first()
                .getOrNull() ?: break
            if (batch.isEmpty()) break
            var stayed = 0
            for (asset in batch) {
                when (processAsset(asset)) {
                    Outcome.Processed -> done++
                    Outcome.Failed -> { done++; failed++ }
                    Outcome.Stayed -> stayed++
                }
                _progress.value = PipelineProgress.Running(done, total, failed)
                capPause?.let {
                    _progress.value = PipelineProgress.Paused(it)
                    return
                }
            }
            yield()
            if (batch.size < BATCH_SIZE) break
            offset += stayed
        }
        _progress.value = PipelineProgress.Finished(done, failed)
    }

    fun shutdown() { scope.cancel() }

    private companion object {
        const val BATCH_SIZE = 10
        const val PLUGIN_FAILURE_THRESHOLD = 5
        const val MARKER_OWNER = "drain"
    }
}
