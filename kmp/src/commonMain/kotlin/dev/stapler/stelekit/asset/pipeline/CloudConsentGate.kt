package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.llm.LlmCredentialStore
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The only place a [GrantedConsent] is minted (ADR-005). Consent is stored per graph, the API key
 * is the one shared Anthropic key, and both are re-read on every [token] call, so a revoke or a
 * deleted key takes effect on the next asset rather than at the next drain.
 */
class CloudConsentGate(
    private val settings: Settings,
    private val credentials: LlmCredentialStore,
) {
    private val _revocations = MutableStateFlow(0L)

    /** Bumps on every [revoke] and [forgetApiKey]; an in-flight upload watches it to cancel itself. */
    val revocations: StateFlow<Long> = _revocations.asStateFlow()

    fun consent(graphId: String): CloudConsent = try {
        decode(settings.getString(keyFor(graphId), ""))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        CloudConsent.NotAsked
    }

    fun grant(graphId: String, atMs: Long, scope: CloudBackfillScope) {
        settings.putString(keyFor(graphId), "$GRANTED|$atMs|${scope.name}")
    }

    /** Immediate and unconfirmed: revoking is the safe direction. */
    fun revoke(graphId: String) {
        settings.putString(keyFor(graphId), REVOKED)
        _revocations.value = _revocations.value + 1
    }

    fun hasApiKey(): Boolean = try {
        !credentials.getApiKey(PROVIDER).isNullOrBlank()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }

    /** Deletes the shared key, which also stops Claude voice formatting. */
    fun forgetApiKey() {
        credentials.deleteApiKey(PROVIDER)
        _revocations.value = _revocations.value + 1
    }

    /** Null unless [graphId] is `Granted` for Anthropic right now and a non-blank key is stored. */
    fun token(graphId: String): GrantedConsent? {
        val granted = consent(graphId) as? CloudConsent.Granted ?: return null
        if (granted.providerId != PROVIDER) return null
        if (credentials.getApiKey(PROVIDER).isNullOrBlank()) return null
        return GrantedConsent.issue(this, graphId, granted)
    }

    private fun decode(raw: String): CloudConsent {
        if (raw == REVOKED) return CloudConsent.Revoked
        val parts = raw.split('|')
        if (parts.size != 3 || parts[0] != GRANTED) return CloudConsent.NotAsked
        val atMs = parts[1].toLongOrNull() ?: return CloudConsent.NotAsked
        val scope = CloudBackfillScope.entries.firstOrNull { it.name == parts[2] } ?: return CloudConsent.NotAsked
        return CloudConsent.Granted(PROVIDER, atMs, scope)
    }

    private fun keyFor(graphId: String) = "cloud_consent_${PROVIDER}_$graphId"

    companion object {
        const val PROVIDER = "anthropic"
        private const val GRANTED = "granted"
        private const val REVOKED = "revoked"
    }
}

/** What the consent dialog shows about existing images: how many, and how many days at the daily cap. */
data class CloudBacklog(val count: Long, val days: Int)

fun estimateCloudBacklog(count: Long, dailyCap: Int): CloudBacklog =
    CloudBacklog(count, if (count <= 0 || dailyCap <= 0) 0 else ((count + dailyCap - 1) / dailyCap).toInt())
