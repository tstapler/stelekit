// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import dev.stapler.stelekit.llm.LlmFeature
import dev.stapler.stelekit.llm.LlmProviderKind
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.llm.LlmSettings

/**
 * Epic 8 Story 8.1: selection now goes through [LlmProviderRegistry]/[LlmSettings] instead of
 * ad hoc `voiceSettings`-key + `deviceLlmProvider`-parameter threading — voice formatting gains
 * multi-provider support (Gemini, custom OpenAI-compatible, iOS on-device, ...) automatically.
 *
 * Selection precedence, matching the pre-Epic-8 `VoicePipelineFactory`'s effective behavior
 * exactly (pitfalls §6.2):
 *  1. An explicit [LlmSettings.getSelectedProviderId] for [LlmFeature.VOICE_FORMATTING] always
 *     wins, resolved via [LlmProviderRegistry.find].
 *  2. Otherwise ("Auto"): prefer an [LlmProviderKind.ON_DEVICE] provider from
 *     [LlmProviderRegistry.availableForFeature], falling back to the first
 *     [LlmProviderKind.REMOTE] one — this mirrors the old
 *     `if (deviceLlmProvider != null && settings.getUseDeviceLlm()) deviceLlmProvider else
 *     <remote>` precedence.
 *
 * [registry] defaults to an empty registry and [llmSettings] to `null` so existing callers that
 * don't care about LLM provider selection (most tests) don't need to construct either — the
 * result in that case is the same as before: [NoOpLlmFormatterProvider] when LLM formatting is
 * enabled but nothing is configured.
 *
 * Now `suspend` — [LlmProviderRegistry.availableForFeature] performs a live availability check
 * per provider (e.g. on-device model readiness), which cannot be done synchronously.
 */
suspend fun buildVoicePipeline(
    audioRecorder: AudioRecorder,
    settings: VoiceSettings,
    directSpeechProvider: DirectSpeechProvider? = null,
    registry: LlmProviderRegistry = LlmProviderRegistry(emptyList()),
    llmSettings: LlmSettings? = null,
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
    } else {
        val selected = llmSettings?.getSelectedProviderId(LlmFeature.VOICE_FORMATTING)
            ?.let { registry.find(it) }
            ?: registry.availableForFeature(LlmFeature.VOICE_FORMATTING).let { candidates ->
                candidates.firstOrNull { it.kind == LlmProviderKind.ON_DEVICE }
                    ?: candidates.firstOrNull { it.kind == LlmProviderKind.REMOTE }
            }
        selected?.formatter ?: NoOpLlmFormatterProvider()
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
