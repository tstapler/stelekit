// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.llm

import dev.stapler.stelekit.platform.security.CredentialAccess

/**
 * Typed wrapper over [CredentialAccess] for LLM/voice provider API keys.
 *
 * Keys are namespaced flat strings consistent with the existing `git_https_token_$id`
 * convention (ADR-011): `llm.<providerId>.api_key`, e.g. `llm.anthropic.api_key`,
 * `llm.openai.api_key`, or `llm.custom.<uuid>.api_key` (pass `"custom.<uuid>"` as
 * [providerId] for the latter — this class does no special-casing of the id shape).
 *
 * Non-secret provider configuration (base URL, model name, etc.) is explicitly NOT stored
 * here — see [dev.stapler.stelekit.llm.LlmSettings] and [CustomProviderConfig].
 */
class LlmCredentialStore(private val credentialStore: CredentialAccess) {

    fun getApiKey(providerId: String): String? = credentialStore.retrieve(keyFor(providerId))

    fun setApiKey(providerId: String, key: String) = credentialStore.store(keyFor(providerId), key)

    fun deleteApiKey(providerId: String) = credentialStore.delete(keyFor(providerId))

    /**
     * Synchronous, durable-before-return write. **Migration-only** — the only intended
     * caller is [dev.stapler.stelekit.llm.LlmCredentialMigration]. Normal credential entry
     * (Settings UI) should keep using [setApiKey], which is fine to be fire-and-forget since
     * it isn't immediately followed by clearing a second, only-copy plaintext source.
     *
     * @return true if the write is confirmed durable, false otherwise.
     */
    fun setApiKeyBlocking(providerId: String, key: String): Boolean =
        credentialStore.storeBlocking(keyFor(providerId), key)

    private fun keyFor(providerId: String): String = "llm.$providerId.api_key"
}
