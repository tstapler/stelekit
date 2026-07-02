// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import dev.stapler.stelekit.platform.Settings

/**
 * Per-feature [LlmProvider] selection storage, parallel to `TagSettings`/`VoiceSettings`.
 * Reuses the existing [Settings] key-value store — no new SQL table.
 */
class LlmSettings(private val platformSettings: Settings) {

    /** `null` means "Auto" — the registry fallback-scans available providers in priority order. */
    fun getSelectedProviderId(feature: LlmFeature): String? =
        platformSettings.getString(key(feature), "").takeIf { it.isNotBlank() }

    fun setSelectedProviderId(feature: LlmFeature, providerId: String?) {
        platformSettings.putString(key(feature), providerId?.trim().orEmpty())
    }

    private fun key(feature: LlmFeature): String =
        "llm.feature.${feature.name.lowercase()}.provider_id"
}
