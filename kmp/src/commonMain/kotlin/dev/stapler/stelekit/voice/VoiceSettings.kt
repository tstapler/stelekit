// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import dev.stapler.stelekit.platform.PlatformSettings

class VoiceSettings(private val platformSettings: PlatformSettings) {

    fun getWhisperApiKey(): String? =
        platformSettings.getString(KEY_WHISPER, "").takeIf { it.isNotBlank() }

    fun setWhisperApiKey(key: String) =
        platformSettings.putString(KEY_WHISPER, key.trim())

    fun getAnthropicKey(): String? =
        platformSettings.getString(KEY_ANTHROPIC, "").takeIf { it.isNotBlank() }

    fun setAnthropicKey(key: String) =
        platformSettings.putString(KEY_ANTHROPIC, key.trim())

    fun getOpenAiKey(): String? =
        platformSettings.getString(KEY_OPENAI, "").takeIf { it.isNotBlank() }

    fun setOpenAiKey(key: String) =
        platformSettings.putString(KEY_OPENAI, key.trim())

    fun getLlmEnabled(): Boolean =
        platformSettings.getBoolean(KEY_LLM_ENABLED, true)

    fun setLlmEnabled(enabled: Boolean) =
        platformSettings.putBoolean(KEY_LLM_ENABLED, enabled)

    companion object {
        private const val KEY_WHISPER = "voice.whisper_key"
        private const val KEY_ANTHROPIC = "voice.anthropic_key"
        private const val KEY_OPENAI = "voice.openai_key"
        private const val KEY_LLM_ENABLED = "voice.llm_enabled"
    }
}
