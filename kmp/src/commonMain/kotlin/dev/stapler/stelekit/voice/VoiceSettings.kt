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

    companion object {
        private const val KEY_WHISPER = "voice.whisper_key"
        private const val KEY_ANTHROPIC = "voice.anthropic_key"
        private const val KEY_OPENAI = "voice.openai_key"
    }
}

fun buildVoicePipeline(audioRecorder: AudioRecorder, settings: VoiceSettings): VoicePipelineConfig {
    val sttProvider: SpeechToTextProvider = settings.getWhisperApiKey()
        ?.let { WhisperSpeechToTextProvider.withDefaults(it) }
        ?: NoOpSpeechToTextProvider()
    val llmProvider: LlmFormatterProvider = settings.getAnthropicKey()
        ?.let { ClaudeLlmFormatterProvider.withDefaults(it) }
        ?: settings.getOpenAiKey()?.let { OpenAiLlmFormatterProvider.withDefaults(it) }
        ?: NoOpLlmFormatterProvider()
    return VoicePipelineConfig(audioRecorder = audioRecorder, sttProvider = sttProvider, llmProvider = llmProvider)
}
