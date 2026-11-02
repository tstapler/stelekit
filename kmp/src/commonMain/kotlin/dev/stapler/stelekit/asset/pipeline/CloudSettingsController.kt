package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.repository.AssetRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the build strips EXIF/GPS before upload; the dialog's metadata line follows it and never claims more. */
const val EXIF_STRIP_SHIPPED: Boolean = true

const val CLOUD_EXIF_REMOVED_LINE = "Location data (EXIF) is removed first."
const val CLOUD_EXIF_SENT_LINE = "Location and camera details stored in the image are sent too."
const val CLOUD_STORAGE_LINE = "Descriptions are saved in this graph and can be searched."
const val CLOUD_WARNING_LINE = "Don't enable this if your images contain anything you wouldn't send to a third party."
const val CLOUD_NETWORK_UNMETERED_LINE = "Only on an unmetered network."
const val CLOUD_NETWORK_ONLINE_LINE = "Only while online."
const val CLOUD_REVOKE_COPY =
    "Cloud descriptions are off. Descriptions already received are kept. Images already sent can't be recalled from Anthropic."
const val CLOUD_POLICY_URL = "https://www.anthropic.com/legal/privacy"

/** What the consent dialog shows, computed when it opens. */
data class CloudConsentDialogModel(
    val graphName: String,
    val existingImages: Long,
    val estimatedDays: Int,
    val sessionCap: Int,
    val dailyCap: Int,
    val exifStripShipped: Boolean,
    /** True where the platform reports metered networks (Android); desktop only says "while online". */
    val meteredAware: Boolean,
    val launchCount: Int,
    val dailyCount: Int,
)

data class CloudUiState(
    val consent: CloudConsent,
    val switchOn: Boolean,
    val keyPresent: Boolean,
    val launchCount: Int,
    val dailyCount: Int,
    val sessionCap: Int,
    val dailyCap: Int,
)

/**
 * What the Plugins page drives for the cloud plugin: the consent dialog's inputs, grant, revoke
 * and key removal for the open graph. Turning the switch off and revoking are the same act and
 * take effect at once; turning it on is only possible through [enable], after the dialog.
 */
class CloudSettingsController(
    private val gate: CloudConsentGate,
    private val registry: PluginRegistry,
    private val ledger: EgressLedger,
    private val assets: AssetRepository,
    private val graphId: () -> String,
    private val graphName: () -> String,
    private val meteredAware: () -> Boolean,
    private val now: () -> Long,
    private val onEnabled: () -> Unit = {},
) {
    private val logger = Logger("CloudSettings")
    // Nothing is read until the page asks (refresh): reading the key store at construction would touch the platform keystore on every graph open.
    private val _state = MutableStateFlow(
        CloudUiState(CloudConsent.NotAsked, false, false, 0, 0, ledger.sessionLimit, ledger.dailyLimit),
    )
    val state: StateFlow<CloudUiState> = _state.asStateFlow()

    private fun read(): CloudUiState {
        val consent = gate.consent(graphId())
        return CloudUiState(
            consent = consent,
            switchOn = consent is CloudConsent.Granted && registry.cloudSwitchOn(),
            keyPresent = gate.hasApiKey(),
            launchCount = ledger.sessionCount,
            dailyCount = ledger.dailyCount,
            sessionCap = ledger.sessionLimit,
            dailyCap = ledger.dailyLimit,
        )
    }

    fun refresh() { _state.value = read() }

    suspend fun dialogModel(): CloudConsentDialogModel {
        val needing = assets.countAssetsNeedingCloudDescription().getOrNull() ?: 0L
        val unprocessed = assets.countUnprocessedAssets().getOrNull() ?: 0L
        val existing = needing + unprocessed
        return CloudConsentDialogModel(
            graphName = graphName(),
            existingImages = existing,
            estimatedDays = estimateCloudBacklog(existing, ledger.dailyLimit).days,
            sessionCap = ledger.sessionLimit,
            dailyCap = ledger.dailyLimit,
            exifStripShipped = EXIF_STRIP_SHIPPED,
            meteredAware = meteredAware(),
            launchCount = ledger.sessionCount,
            dailyCount = ledger.dailyCount,
        )
    }

    /** "Enable uploads": records consent for this graph only, switches the plugin on and starts a drain. */
    fun enable(scope: CloudBackfillScope) {
        gate.grant(graphId(), now(), scope)
        registry.setEnabled(PluginEnableKeys.CLOUD_CLAUDE, true)
        registry.onCloudStateChanged()
        logger.info("cloud descriptions enabled scope=${scope.name}")
        refresh()
        onEnabled()
    }

    /** Switch off or "Revoke consent": immediate, no confirmation. */
    fun revoke() {
        gate.revoke(graphId())
        registry.setEnabled(PluginEnableKeys.CLOUD_CLAUDE, false)
        registry.onCloudStateChanged()
        logger.info("cloud descriptions revoked")
        refresh()
    }

    /** "Forget API key": also stops Claude voice formatting, since the key is shared. */
    fun forgetApiKey() {
        gate.forgetApiKey()
        registry.onCloudStateChanged()
        refresh()
    }
}
