// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.tags

import dev.stapler.stelekit.platform.Settings

/**
 * Persisted settings for the auto-tag suggestion feature.
 * Reuses the same [Settings] store as VoiceSettings; keys are namespaced with "tags.".
 */
class TagSettings(private val platformSettings: Settings) {

    /** Whether the tag suggestion feature is enabled at all. */
    fun isEnabled(): Boolean = platformSettings.getBoolean(KEY_ENABLED, true)
    fun setEnabled(enabled: Boolean) = platformSettings.putBoolean(KEY_ENABLED, enabled)

    /**
     * Whether the LLM tier is enabled (requires an Anthropic or OpenAI key in VoiceSettings).
     * When false only the local AhoCorasick tier runs.
     */
    fun isLlmTierEnabled(): Boolean = platformSettings.getBoolean(KEY_LLM_TIER_ENABLED, true)
    fun setLlmTierEnabled(enabled: Boolean) = platformSettings.putBoolean(KEY_LLM_TIER_ENABLED, enabled)

    companion object {
        private const val KEY_ENABLED = "tags.enabled"
        private const val KEY_LLM_TIER_ENABLED = "tags.llm_tier_enabled"
    }
}
