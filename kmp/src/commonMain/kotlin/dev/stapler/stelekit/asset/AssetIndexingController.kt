package dev.stapler.stelekit.asset

import dev.stapler.stelekit.asset.pipeline.AssetBuildFlags
import dev.stapler.stelekit.asset.pipeline.PipelineRunMarker
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.service.AttachmentResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the `asset_pipeline_enabled` switch and decides when [AssetIndexService] runs.
 *
 * The gate lives here, in the entry point, not only in the UI: [isActive] is false unless the
 * build exposes the switch ([toggleVisible], hidden in release variants before the heap gate) AND
 * the stored flag is true, so a release build never starts the backfill and never registers
 * attachments even if the key were somehow set.
 */
class AssetIndexingController(
    private val settings: Settings,
    private val indexService: AssetIndexService,
    private val toggleVisibleProvider: () -> Boolean = { AssetBuildFlags.PIPELINE_TOGGLE_VISIBLE },
    /** Shared with the plugin drain so one marker covers the whole pipeline. */
    private val runMarker: PipelineRunMarker = PipelineRunMarker(settings),
) {
    private val logger = Logger("AssetIndexingController")

    private companion object {
        const val MARKER_OWNER = "index"
    }

    private class GraphContext(
        val notesPath: EffectiveNotesPath,
        val writeActor: DatabaseWriteActor?,
        val fileSystem: FileSystem,
    )

    private var graph: GraphContext? = null

    // Evaluated before the flag is read: a tripped breaker writes the flag off.
    private val _breakerTripped = MutableStateFlow(runMarker.evaluateAtLaunch())
    private val _enabled = MutableStateFlow(readFlag())

    /** True after repeated unclean exits turned the pipeline off; cleared when the owner re-enables it. */
    val breakerTripped: StateFlow<Boolean> = _breakerTripped.asStateFlow()

    private val markerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // A finished walk is a clean stop; every other stop path ends the marker explicitly.
        markerScope.launch {
            indexService.progress.collect { if (it is AssetIndexProgress.Finished) runMarker.end(MARKER_OWNER) }
        }
    }

    val toggleVisible: Boolean get() = toggleVisibleProvider()

    /** The stored flag; independent of whether this build lets the owner see it. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    val progress: StateFlow<AssetIndexProgress> = indexService.progress

    val isActive: Boolean get() = toggleVisible && _enabled.value

    private fun readFlag(): Boolean = try {
        settings.getBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, false)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }

    /** Persists the flag and starts or stops the backfill immediately, without a restart. */
    fun setEnabled(value: Boolean) {
        if (!toggleVisible) return
        if (value) {
            runMarker.reset()
            _breakerTripped.value = false
        }
        settings.putBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, value)
        _enabled.value = value
        reconcile()
    }

    /** The graph finished loading; start indexing if the switch is on. */
    fun onGraphReady(notesPath: EffectiveNotesPath, writeActor: DatabaseWriteActor?, fileSystem: FileSystem) {
        graph = GraphContext(notesPath, writeActor, fileSystem)
        reconcile()
    }

    /** The graph is (re)loading or closing; `loadGraph` must never overlap the backfill. */
    fun onGraphNotReady() {
        graph = null
        stopIndexing()
    }

    /**
     * The OS reported memory pressure: drop the running backfill so its buffers can be reclaimed.
     * It restarts on the next graph-ready or switch flip, not automatically.
     */
    fun onMemoryPressure() {
        stopIndexing()
    }

    fun close() {
        graph = null
        runMarker.end(MARKER_OWNER)
        markerScope.cancel()
        indexService.close()
    }

    private fun stopIndexing() {
        indexService.stopBackfill()
        runMarker.end(MARKER_OWNER)
    }

    private fun reconcile() {
        val ctx = graph
        if (isActive && ctx != null) {
            runMarker.begin(MARKER_OWNER)
            indexService.startBackfill(ctx.notesPath, ctx.writeActor, ctx.fileSystem)
        } else {
            stopIndexing()
        }
    }

    /**
     * Records a file the attach flow just copied into `assets/`. A failure here is logged and
     * swallowed: indexing must never fail an attach.
     */
    suspend fun registerAttachment(result: AttachmentResult) {
        val ctx = graph
        if (!isActive || ctx == null) return
        try {
            val absolute = ctx.notesPath.child(result.relativePath.removePrefix("../"))
            indexService.registerAsset(
                filePath = absolute,
                notesPath = ctx.notesPath,
                writeActor = ctx.writeActor,
                fileSystem = ctx.fileSystem,
            ).onLeft { logger.warn("Indexing attachment failed: ${it.message}") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Indexing attachment failed: ${e.message}")
        }
    }
}
