// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

/**
 * Pure validation for a custom OpenAI-compatible provider's base URL (Epic 6 Story 6.3).
 *
 * Enforces the HTTP-vs-HTTPS/loopback constraint from Story 3.2: plaintext `http://` is only
 * acceptable for loopback endpoints (Ollama, LM Studio, and similar local servers) — a
 * non-loopback `http://` URL would send the API key in the clear over the network, so it is
 * rejected here as inline validation *before* [CustomProviderConfig] is ever constructed
 * (Story 6.3 acceptance criteria), rather than a silent `require()` crash deeper in the stack.
 */
object CustomProviderUrlValidation {

    private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")

    /** Returns `null` if [url] is valid, otherwise a human-readable error message. */
    fun validate(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return "Base URL is required."
        if (!trimmed.contains("://")) return "URL must start with http:// or https://"

        val scheme = trimmed.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return "URL must use http:// or https://"

        if (scheme == "http") {
            val host = extractHost(trimmed)
                ?: return "Could not parse a host from this URL."
            if (!isLoopbackHost(host)) {
                return "Non-HTTPS URLs are only allowed for localhost/loopback addresses " +
                    "(e.g. Ollama, LM Studio). Remote endpoints must use HTTPS."
            }
        }

        return null
    }

    fun isValid(url: String): Boolean = validate(url) == null

    private fun extractHost(url: String): String? {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isBlank()) return null
        val hostPort = afterScheme.substringBefore("/")
        // IPv6 literal, e.g. [::1]:11434 — keep the brackets for comparison against loopbackHosts.
        if (hostPort.startsWith("[")) {
            return hostPort.substringBefore("]") + "]"
        }
        val host = hostPort.substringBefore(":")
        return host.ifBlank { null }
    }

    private fun isLoopbackHost(host: String): Boolean =
        host in loopbackHosts || host.startsWith("127.")
}
