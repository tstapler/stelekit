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

    /**
     * Clears the plaintext Anthropic key. Used by [dev.stapler.stelekit.llm.LlmCredentialMigration]
     * once the value has been durably migrated into `LlmCredentialStore` (ADR-011) — this is
     * the only intended caller; do not call this before a durable write is confirmed.
     */
    fun clearAnthropicKey() = platformSettings.putString(KEY_ANTHROPIC, "")

    fun getOpenAiKey(): String? =
        platformSettings.getString(KEY_OPENAI, "").takeIf { it.isNotBlank() }

    fun setOpenAiKey(key: String) =
        platformSettings.putString(KEY_OPENAI, key.trim())

    /**
     * Clears the plaintext OpenAI key. Used by [dev.stapler.stelekit.llm.LlmCredentialMigration]
     * once the value has been durably migrated into `LlmCredentialStore` (ADR-011) — this is
     * the only intended caller; do not call this before a durable write is confirmed.
     */
    fun clearOpenAiKey() = platformSettings.putString(KEY_OPENAI, "")

    fun getLlmEnabled(): Boolean =
        platformSettings.getBoolean(KEY_LLM_ENABLED, true)

    fun setLlmEnabled(enabled: Boolean) =
        platformSettings.putBoolean(KEY_LLM_ENABLED, enabled)

    fun getUseDeviceStt(): Boolean =
        platformSettings.getBoolean(KEY_USE_DEVICE_STT, true)

    fun setUseDeviceStt(enabled: Boolean) =
        platformSettings.putBoolean(KEY_USE_DEVICE_STT, enabled)

    fun getIncludeRawTranscript(): Boolean =
        platformSettings.getBoolean(KEY_INCLUDE_RAW_TRANSCRIPT, true)

    fun setIncludeRawTranscript(enabled: Boolean) =
        platformSettings.putBoolean(KEY_INCLUDE_RAW_TRANSCRIPT, enabled)

    fun getTranscriptPageWordThreshold(): Int =
        maxOf(1, platformSettings.getString(KEY_TRANSCRIPT_PAGE_WORD_THRESHOLD,
            DEFAULT_TRANSCRIPT_PAGE_WORD_THRESHOLD.toString()).toIntOrNull()
            ?: DEFAULT_TRANSCRIPT_PAGE_WORD_THRESHOLD)

    fun setTranscriptPageWordThreshold(threshold: Int) =
        platformSettings.putString(KEY_TRANSCRIPT_PAGE_WORD_THRESHOLD, maxOf(1, threshold).toString())

    companion object {
        const val DEFAULT_TRANSCRIPT_PAGE_WORD_THRESHOLD = 20
        private const val KEY_WHISPER = "voice.whisper_key"
        private const val KEY_ANTHROPIC = "voice.anthropic_key"
        private const val KEY_OPENAI = "voice.openai_key"
        private const val KEY_LLM_ENABLED = "voice.llm_enabled"
        private const val KEY_USE_DEVICE_STT = "voice.use_device_stt"

        /**
         * Public (not private) and no longer backed by a getter/setter pair — Epic 8 Story
         * 8.1c removed [getUseDeviceLlm]/[setUseDeviceLlm] (superseded by per-feature
         * selection in `LlmSettings`/`LlmProviderRegistry`), but the raw key is kept public
         * so [dev.stapler.stelekit.llm.LlmCredentialMigration] can perform its one-time,
         * one-directional read of the legacy flag to preserve an existing user's effective
         * on-device choice across the upgrade (see that class's migration step). Do not add a
         * new getter/setter here — that would resurrect the two-systems-disagree risk this
         * migration exists to close out.
         */
        const val KEY_USE_DEVICE_LLM_LEGACY = "voice.use_device_llm"
        private const val KEY_INCLUDE_RAW_TRANSCRIPT = "voice.include_raw_transcript"
        private const val KEY_TRANSCRIPT_PAGE_WORD_THRESHOLD = "voice.transcript_page_word_threshold"
    }
}
