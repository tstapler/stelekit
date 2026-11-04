package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.asset.AssetIndexingController
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.Settings
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
 * What Settings > Plugins drives: the per-plugin switches ([registry]), the pipeline's pause,
 * resume and cancel, and the master indexing switch ([indexing]). Turning a plugin switch on or
 * off re-derives the plugin set and restarts the drain without an app restart.
 */
class AssetPluginsController(
    val registry: PluginRegistry,
    private val pipeline: AssetPipelineService?,
    val indexing: AssetIndexingController,
    private val settings: Settings? = null,
    private val power: PowerSource = AlwaysPowered,
    /** Cloud description consent and switch; null where the platform has no cloud plugin wiring. */
    val cloud: CloudSettingsController? = null,
) {
    private val logger = Logger("AssetPipelinePlugins")

    private val idle = MutableStateFlow<PipelineProgress>(PipelineProgress.Idle)
    private val _stoppedByUser = MutableStateFlow(false)

    private val _waitingForCharger = MutableStateFlow(false)
    private var processNowOverride = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True while a requested drain waits for external power; the row offers "Process now". */
    val waitingForCharger: StateFlow<Boolean> = _waitingForCharger.asStateFlow()

    private val _startFailure = MutableStateFlow<String?>(null)

    /** Display name of a plugin that could not start ("text recognition"); its switch has been returned to off. */
    val startFailure: StateFlow<String?> = _startFailure.asStateFlow()

    init {
        pipeline?.let { service ->
            scope.launch {
                service.pluginInitFailures.collect { pluginId ->
                    val settingsId = PluginRegistry.settingsIdFor(pluginId) ?: return@collect
                    logger.warn("Plugin $pluginId could not start; switching it off")
                    try {
                        registry.setEnabled(settingsId, false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        registry.refresh()
                    }
                    _startFailure.value = PluginRegistry.displayName(settingsId)
                }
            }
        }
        scope.launch { power.isCharging.collect { charging -> if (charging && _waitingForCharger.value) requestBackfill() } }
    }

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
        if (registry.enabledCount.value == 0) {
            _waitingForCharger.value = false
            service.cancel()
        } else {
            requestBackfill()
        }
    }

    /** Starts the drain, or parks it as "Waiting for charger" when on battery and the owner has not overridden. */
    fun requestBackfill() {
        val service = pipeline ?: return
        if (!indexing.isActive) return
        _stoppedByUser.value = false
        if (!power.isCharging.value && !processNowOverride) {
            _waitingForCharger.value = true
            return
        }
        _waitingForCharger.value = false
        service.scheduleBackfill()
    }

    /** The owner chose to process now, on battery. */
    fun processNow() {
        processNowOverride = true
        requestBackfill()
    }

    fun close() {
        scope.cancel()
    }

    fun dismissStartFailure() {
        _startFailure.value = null
    }

    fun pause() {
        pipeline?.pause(PauseReason.User)
    }

    fun resume() {
        pipeline?.resume()
    }

    fun cancel() {
        _stoppedByUser.value = true
        _waitingForCharger.value = false
        processNowOverride = false
        pipeline?.cancel()
    }

    fun startAgain() = requestBackfill()

    /** Clears the failed flags and drains again (the "Retry failed" button). */
    fun retryFailed() {
        _stoppedByUser.value = false
        pipeline?.retryFailed()
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
