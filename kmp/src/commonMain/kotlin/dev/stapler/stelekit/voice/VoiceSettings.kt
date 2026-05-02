// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import dev.stapler.stelekit.platform.Settings

class VoiceSettings(private val platformSettings: Settings) {

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

    fun getUseDeviceStt(): Boolean =
        platformSettings.getBoolean(KEY_USE_DEVICE_STT, true)

    fun setUseDeviceStt(enabled: Boolean) =
        platformSettings.putBoolean(KEY_USE_DEVICE_STT, enabled)

    fun getUseDeviceLlm(): Boolean =
        platformSettings.getBoolean(KEY_USE_DEVICE_LLM, false)

    fun setUseDeviceLlm(enabled: Boolean) =
        platformSettings.putBoolean(KEY_USE_DEVICE_LLM, enabled)

    companion object {
        private const val KEY_WHISPER = "voice.whisper_key"
        private const val KEY_ANTHROPIC = "voice.anthropic_key"
        private const val KEY_OPENAI = "voice.openai_key"
        private const val KEY_LLM_ENABLED = "voice.llm_enabled"
        private const val KEY_USE_DEVICE_STT = "voice.use_device_stt"
        private const val KEY_USE_DEVICE_LLM = "voice.use_device_llm"
    }
}
