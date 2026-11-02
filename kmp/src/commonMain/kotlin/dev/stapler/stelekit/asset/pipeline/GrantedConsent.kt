package dev.stapler.stelekit.asset.pipeline

/** Where consent applies: which images a grant covers. */
enum class CloudBackfillScope { NewOnly, IncludeExisting }

/** The stored consent state for one graph and provider. */
sealed interface CloudConsent {
    data object NotAsked : CloudConsent
    data class Granted(val providerId: String, val atMs: Long, val scope: CloudBackfillScope) : CloudConsent
    data object Revoked : CloudConsent
}

/**
 * Proof that [graphId] has granted [providerId] and the API key exists, read at the moment the gate
 * was asked. The upload path takes one as a parameter, so code without a token cannot call it.
 * Only [CloudConsentGate] mints one (audited by `GrantedConsentMintSiteAuditTest`).
 */
class GrantedConsent private constructor(
    val graphId: String,
    val providerId: String,
    val grantedAtMs: Long,
    val scope: CloudBackfillScope,
) {
    companion object {
        /** Takes the gate as a witness so the call site is the gate's own code. */
        @Suppress("UnusedParameter")
        internal fun issue(gate: CloudConsentGate, graphId: String, granted: CloudConsent.Granted): GrantedConsent =
            GrantedConsent(graphId, granted.providerId, granted.atMs, granted.scope)
    }
}
