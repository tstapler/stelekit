// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

fun buildVoicePipeline(
    audioRecorder: AudioRecorder,
    settings: VoiceSettings,
    directSpeechProvider: DirectSpeechProvider? = null,
    deviceLlmProvider: LlmFormatterProvider? = null,
): VoicePipelineConfig {
    val sttProvider: SpeechToTextProvider = settings.getWhisperApiKey()
        ?.let { WhisperSpeechToTextProvider.withDefaults(it) }
        ?: SpeechToTextProvider { _ ->
            TranscriptResult.Failure.ApiError(
                0,
                "No API key configured — add a Whisper key in Settings → Voice Capture",
            )
        }
    val llmProvider: LlmFormatterProvider = if (!settings.getLlmEnabled()) {
        NoOpLlmFormatterProvider()
    } else if (deviceLlmProvider != null && settings.getUseDeviceLlm()) {
        deviceLlmProvider
    } else {
        settings.getAnthropicKey()?.let { ClaudeLlmFormatterProvider.withDefaults(it) }
            ?: settings.getOpenAiKey()?.let { OpenAiLlmFormatterProvider.withDefaults(it) }
            ?: NoOpLlmFormatterProvider()
    }
    return VoicePipelineConfig(
        audioRecorder = audioRecorder,
        sttProvider = sttProvider,
        llmProvider = llmProvider,
        directSpeechProvider = directSpeechProvider,
        includeRawTranscript = settings.getIncludeRawTranscript(),
        transcriptPageWordThreshold = settings.getTranscriptPageWordThreshold(),
    )
}
