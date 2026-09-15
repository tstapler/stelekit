// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.llm

import dev.stapler.stelekit.platform.Settings

/**
 * Non-secret configuration for a generic OpenAI-compatible custom provider (Ollama/LM
 * Studio/Azure OpenAI/OpenRouter/etc). The API key for [id] lives separately in
 * [LlmCredentialStore] — never here (ADR-011: secrets and non-secret config are stored
 * separately, mirroring [dev.stapler.stelekit.git.model.GitConfig]).
 */
data class CustomProviderConfig(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val model: String,
    val allowInsecureHttp: Boolean = false,
)

/**
 * Non-secret LLM provider settings, backed by [Settings]. Custom-provider ids are stored as
 * a comma-joined string list (no JSON blob — every field addition is a new namespaced key,
 * not a schema migration). Also holds per-feature [LlmProvider] selection, parallel to
 * `TagSettings`/`VoiceSettings` — no new SQL table.
 */
class LlmSettings(private val settings: Settings) {

    /** `null` means "Auto" — the registry fallback-scans available providers in priority order. */
    fun getSelectedProviderId(feature: LlmFeature): String? =
        settings.getString(selectedProviderKey(feature), "").takeIf { it.isNotBlank() }

    fun setSelectedProviderId(feature: LlmFeature, providerId: String?) {
        settings.putString(selectedProviderKey(feature), providerId?.trim().orEmpty())
    }

    fun getCustomProviderIds(): List<String> =
        settings.getString(KEY_CUSTOM_PROVIDER_IDS, "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun getCustomProviderConfig(id: String): CustomProviderConfig? {
        if (id !in getCustomProviderIds()) return null
        val baseUrl = settings.getString(baseUrlKey(id), "")
        if (baseUrl.isBlank()) return null
        return CustomProviderConfig(
            id = id,
            displayName = settings.getString(displayNameKey(id), ""),
            baseUrl = baseUrl,
            model = settings.getString(modelKey(id), ""),
            allowInsecureHttp = settings.getBoolean(allowInsecureHttpKey(id), false),
        )
    }

    fun setCustomProviderConfig(config: CustomProviderConfig) {
        val ids = getCustomProviderIds()
        if (config.id !in ids) {
            settings.putString(KEY_CUSTOM_PROVIDER_IDS, (ids + config.id).joinToString(","))
        }
        settings.putString(displayNameKey(config.id), config.displayName)
        settings.putString(baseUrlKey(config.id), config.baseUrl)
        settings.putString(modelKey(config.id), config.model)
        settings.putBoolean(allowInsecureHttpKey(config.id), config.allowInsecureHttp)
    }

    fun removeCustomProviderConfig(id: String) {
        val remainingIds = getCustomProviderIds().filter { it != id }
        settings.putString(KEY_CUSTOM_PROVIDER_IDS, remainingIds.joinToString(","))
        settings.putString(displayNameKey(id), "")
        settings.putString(baseUrlKey(id), "")
        settings.putString(modelKey(id), "")
        settings.putBoolean(allowInsecureHttpKey(id), false)
    }

    private fun selectedProviderKey(feature: LlmFeature): String =
        "llm.feature.${feature.name.lowercase()}.provider_id"

    companion object {
        private const val KEY_CUSTOM_PROVIDER_IDS = "llm.custom_provider_ids"
        private fun displayNameKey(id: String) = "llm.custom.$id.display_name"
        private fun baseUrlKey(id: String) = "llm.custom.$id.base_url"
        private fun modelKey(id: String) = "llm.custom.$id.model"
        private fun allowInsecureHttpKey(id: String) = "llm.custom.$id.allow_insecure_http"
    }
}
