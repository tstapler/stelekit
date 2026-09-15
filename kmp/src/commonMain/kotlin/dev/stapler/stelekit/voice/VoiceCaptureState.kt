// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

enum class PipelineStage { RECORDING, TRANSCRIBING, FORMATTING, JOURNAL }

enum class VoiceErrorKind { PERMISSION_DENIED, NO_GRAPH, GENERIC }

sealed interface VoiceCaptureState {
    data object Idle : VoiceCaptureState
    data object Recording : VoiceCaptureState
    data object Transcribing : VoiceCaptureState
    data object Formatting : VoiceCaptureState
    data class Done(
        val insertedText: String,
        val isLikelyTruncated: Boolean = false,
        val transcriptPageTitle: String? = null,
        val savedToPageName: String? = null,
        val suggestedTags: List<dev.stapler.stelekit.tags.TagSuggestion> = emptyList(),
    ) : VoiceCaptureState
    data class Error(
        val stage: PipelineStage,
        val message: String,
        val kind: VoiceErrorKind = VoiceErrorKind.GENERIC,
    ) : VoiceCaptureState
}
