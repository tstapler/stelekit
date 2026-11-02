package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.asset.AssetIndexingController
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What Settings > Plugins drives: the per-plugin switches ([registry]), the pipeline's pause,
 * resume and cancel, and the master indexing switch ([indexing]). Turning a plugin switch on or
 * off re-derives the plugin set and restarts the drain without an app restart.
 */
class AssetPluginsController(
    val registry: PluginRegistry,
    private val pipeline: AssetPipelineService?,
    val indexing: AssetIndexingController,
    private val settings: Settings? = null,
) {
    private val logger = Logger("AssetPipelinePlugins")

    private val idle = MutableStateFlow<PipelineProgress>(PipelineProgress.Idle)
    private val _stoppedByUser = MutableStateFlow(false)

    private val _promptDismissed = MutableStateFlow(readPromptDismissed())

    /** True once the owner tapped "Not now" on the Assets "Make images searchable" card. */
    val promptDismissed: StateFlow<Boolean> = _promptDismissed.asStateFlow()

    val progress: StateFlow<PipelineProgress> get() = pipeline?.progress ?: idle

    /** True after the owner cancelled a run; the row then offers "Start again". */
    val stoppedByUser: StateFlow<Boolean> = _stoppedByUser.asStateFlow()

    /**
     * Persists the switch and starts the drain (or stops it when no plugin remains). A failing
     * settings write leaves the stored value unchanged and returns Left so the UI snaps back.
     */
    fun setPluginEnabled(pluginId: String, enabled: Boolean): Either<DomainError, Unit> {
        try {
            registry.setEnabled(pluginId, enabled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            registry.refresh()
            logger.warn("Could not save plugin switch $pluginId: ${e.message}")
            return DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
        applyPluginChange()
        return Unit.right()
    }

    private fun applyPluginChange() {
        val service = pipeline ?: return
        if (!indexing.isActive) return
        _stoppedByUser.value = false
        if (registry.enabledCount.value == 0) service.cancel() else service.scheduleBackfill()
    }

    fun pause() {
        pipeline?.pause(PauseReason.User)
    }

    fun resume() {
        pipeline?.resume()
    }

    fun cancel() {
        _stoppedByUser.value = true
        pipeline?.cancel()
    }

    fun startAgain() {
        _stoppedByUser.value = false
        if (indexing.isActive) pipeline?.scheduleBackfill()
    }

    /** Persists the card dismissal; it stays hidden across launches until [clearPromptDismissal]. */
    fun dismissPrompt() = writePromptDismissed(true)

    fun clearPromptDismissal() = writePromptDismissed(false)

    private fun writePromptDismissed(value: Boolean) {
        try {
            settings?.putBoolean(PROMPT_DISMISSED_KEY, value)
            _promptDismissed.value = value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Could not save suggestion dismissal: ${e.message}")
        }
    }

    private fun readPromptDismissed(): Boolean = try {
        settings?.getBoolean(PROMPT_DISMISSED_KEY, false) ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }

    companion object {
        const val PROMPT_DISMISSED_KEY: String = "asset_searchable_prompt_dismissed"
    }
}
