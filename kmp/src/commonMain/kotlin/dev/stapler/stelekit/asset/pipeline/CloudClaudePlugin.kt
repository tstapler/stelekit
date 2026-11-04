package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.llm.AnthropicContentBlock
import dev.stapler.stelekit.llm.AnthropicMessagesClient
import dev.stapler.stelekit.llm.AnthropicRequest
import dev.stapler.stelekit.llm.AnthropicResult
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.image.ImageDownscalerPort
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Describes image assets with Anthropic and stores the text as a cloud description (never as OCR).
 * Nothing is sent unless [CloudConsentGate] hands out a [GrantedConsent] for [graphId] at the
 * moment of the call; the token is also checked in [canProcess], so a revoke mid-drain skips every
 * later asset. The image is downscaled and stripped of EXIF/GPS first, caps are counted by [ledger],
 * and the API key is read from the credential store inside [client] on each request.
 */
class CloudClaudePlugin(
    private val config: CloudEnrichmentConfig,
    private val client: AnthropicMessagesClient,
    private val gate: CloudConsentGate,
    private val graphId: String,
    private val ledger: EgressLedger,
    private val fileSystem: FileSystem,
    private val downscaler: ImageDownscalerPort,
    private val network: () -> CloudNetwork = { CloudNetwork.Online },
) : AssetPipelinePlugin {
    override val id: String = "cloud_claude"
    private val logger = Logger("CloudClaudePlugin")

    override fun canProcess(asset: AssetEntry): Boolean {
        if (!config.enabled || config.provider != CloudProvider.CLAUDE) return false
        if (asset.mediaType != AssetMediaType.IMAGE || asset.mlTagsSource == CLOUD_TAGS_SOURCE) return false
        val consent = admitted(asset) ?: return false
        if (!asset.mlProcessed) return true
        // Already ML-processed images are only described when the owner chose IncludeExisting.
        return consent.scope == CloudBackfillScope.IncludeExisting && asset.cloudDescription == null && !asset.mlFailed
    }

    /** True while consent for this graph covers images that were processed before it was granted. */
    fun wantsBackfill(): Boolean = gate.token(graphId)?.scope == CloudBackfillScope.IncludeExisting

    private fun networkSkip(): AssetPipelineResult.Skipped? = when (network()) {
        CloudNetwork.Online -> null
        CloudNetwork.Offline, CloudNetwork.Metered -> AssetPipelineResult.Skipped(PauseReason.Network)
    }

    override suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> {
        val consent = admitted(asset) ?: return AssetPipelineResult.Skipped().right()
        return processAsset(asset, consent)
    }

    /** The upload path. The [consent] parameter is what makes it uncallable without the gate. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun processAsset(asset: AssetEntry, consent: GrantedConsent): Either<DomainError, AssetPipelineResult> {
        networkSkip()?.let { return it.right() }
        when (val reservation = ledger.tryReserve(asset.sizeBytes)) {
            EgressLedger.Reservation.Allowed -> Unit
            EgressLedger.Reservation.SessionCap -> return AssetPipelineResult.Skipped(PauseReason.SessionCap).right()
            EgressLedger.Reservation.DailyCap -> return AssetPipelineResult.Skipped(PauseReason.DailyCap).right()
            is EgressLedger.Reservation.TooLarge -> {
                logger.info("skipping ${asset.uuid.value}: ${reservation.bytes} bytes exceeds ${reservation.maxBytes}")
                return AssetPipelineResult.Skipped().right()
            }
        }
        val prepared = prepare(asset)
        val bytes = prepared.getOrNull() ?: return prepared.leftOrNull()!!.left()
        ledger.recordUpload(asset.uuid.value, bytes.size)
        return describe(bytes)
    }

    private fun admitted(asset: AssetEntry): GrantedConsent? {
        val consent = gate.token(graphId) ?: return null
        val beforeConsent = asset.importedAtMs < consent.grantedAtMs
        return if (consent.scope == CloudBackfillScope.NewOnly && beforeConsent) null else consent
    }

    private fun prepare(asset: AssetEntry): Either<DomainError, ByteArray> {
        val original = fileSystem.readFileBytes(asset.filePath)
            ?: return DomainError.DatabaseError.ReadFailed("Cannot read ${asset.filePath}").left()
        return prepareForModel(original, config.maxEdgePx, downscaler).map { ExifStripper.strip(it) }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun describe(bytes: ByteArray): Either<DomainError, AssetPipelineResult> {
        val request = AnthropicRequest(
            model = config.model,
            maxTokens = config.maxTokens,
            system = SYSTEM_PROMPT,
            content = listOf(
                AnthropicContentBlock.Image(mediaTypeOf(bytes), Base64.encode(bytes)),
                AnthropicContentBlock.Text("Describe this image."),
            ),
        )
        val result = sendUnlessRevoked(request) ?: return AssetPipelineResult.Skipped().right()
        return when (result) {
            is AnthropicResult.Success -> AssetPipelineResult.CloudDescription(result.text, emptyList()).right()
            AnthropicResult.Failure.MissingKey -> AssetPipelineResult.Skipped().right()
            is AnthropicResult.Failure.Http ->
                DomainError.NetworkError.HttpError(result.status, "Anthropic request failed: HTTP ${result.status}").left()
            AnthropicResult.Failure.Network ->
                DomainError.NetworkError.RequestFailed("Anthropic request failed: network error").left()
            AnthropicResult.Failure.Unexpected ->
                DomainError.NetworkError.RequestFailed("Anthropic request failed: unexpected error").left()
        }
    }

    /** Cancels the call the moment consent is revoked or the key is forgotten; null means it was cancelled that way. */
    private suspend fun sendUnlessRevoked(request: AnthropicRequest): AnthropicResult? {
        val epoch = gate.revocations.value
        return coroutineScope {
            val call = async { client.send(request) }
            val watcher = launch {
                gate.revocations.first { it != epoch }
                call.cancel()
            }
            try {
                call.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                null
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun mediaTypeOf(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
        bytes.size > 11 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "image/webp"
        else -> "image/jpeg"
    }

    companion object {
        const val CLOUD_TAGS_SOURCE = "CLOUD_CLAUDE"
        private const val SYSTEM_PROMPT =
            "Describe the image in one or two plain sentences so it can be found by text search. " +
                "Name any visible company, product or document type. Reply with only the description."
    }
}
