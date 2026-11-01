// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.security

/**
 * The one place that knows what a credential looks like. Every sink that can show text to a human or
 * a file (Logger sinks, the diagnostics export, git error messages, the developer console) goes
 * through here; `RedactorSingleSiteAuditTest` fails if a secret-pattern literal appears in any other
 * production file.
 *
 * Limits: matches known token shapes, URL userinfo and caller-supplied exact secrets only. A secret
 * that was hex- or base64-encoded before it reached the text is not recognised.
 */
object Redactor {
    const val MASK = "[redacted]"
    private const val QUERY_MASK = "<redacted>"

    private val TOKEN = Regex(
        "(?<![A-Za-z0-9])(?:(?:ghp_|gho_|ghu_|ghs_|ghr_|github_pat_|glpat-)[A-Za-z0-9_\\-]+|AKIA[0-9A-Z]{16})",
    )
    private val URL_USERINFO = Regex("(?<=//)[^/@\\s]+@")
    private val QUERY_VALUE = Regex("([?&][^=&\\s#]+=)[^&\\s#]*")

    /** Exact secrets shorter than this are ignored: masking a 2-character value would shred ordinary text. */
    const val MIN_EXACT_SECRET_LENGTH = 6

    /**
     * Masks known token shapes, `//user:secret@` userinfo (becomes `//[redacted]@`) and every value in
     * [exactSecrets]. Idempotent.
     */
    fun redact(text: String, exactSecrets: Collection<String> = emptyList()): String {
        var out = TOKEN.replace(text, MASK).replace(URL_USERINFO, "$MASK@")
        for (secret in exactSecrets) {
            if (secret.length >= MIN_EXACT_SECRET_LENGTH) out = out.replace(secret, MASK)
        }
        return out
    }

    /**
     * Form for git/HTTP error text that is shown inline: also drops URL userinfo entirely (the host stays
     * readable) and masks every query-string value, since transports echo `?token=...` back.
     */
    fun redactGitMessage(text: String): String =
        TOKEN.replace(text, MASK).replace(URL_USERINFO, "").replace(QUERY_VALUE, "$1$QUERY_MASK")

    /** Removes only `scheme://userinfo@` userinfo; used where the message must otherwise stay byte-identical. */
    fun stripUrlUserinfo(text: String): String = URL_USERINFO.replace(text, "")
}
