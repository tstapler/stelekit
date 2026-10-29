package dev.stapler.stelekit.asset

import dev.stapler.stelekit.asset.pipeline.AssetBuildFlags
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.service.AttachmentResult
import kotlinx.coroutines.CancellationException
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
) {
    private val logger = Logger("AssetIndexingController")

    private class GraphContext(
        val notesPath: EffectiveNotesPath,
        val writeActor: DatabaseWriteActor?,
        val fileSystem: FileSystem,
    )

    private var graph: GraphContext? = null
    private val _enabled = MutableStateFlow(readFlag())

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
        indexService.stopBackfill()
    }

    fun close() {
        graph = null
        indexService.close()
    }

    private fun reconcile() {
        val ctx = graph
        if (isActive && ctx != null) {
            indexService.startBackfill(ctx.notesPath, ctx.writeActor, ctx.fileSystem)
        } else {
            indexService.stopBackfill()
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
