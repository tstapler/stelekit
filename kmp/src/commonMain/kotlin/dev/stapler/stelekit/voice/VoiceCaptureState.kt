// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

enum class PipelineStage { RECORDING, TRANSCRIBING, FORMATTING, JOURNAL }

sealed interface VoiceCaptureState {
    data object Idle : VoiceCaptureState
    data object Recording : VoiceCaptureState
    data object Transcribing : VoiceCaptureState
    data object Formatting : VoiceCaptureState
    data class Done(val insertedText: String, val isLikelyTruncated: Boolean = false) : VoiceCaptureState
    data class Error(val stage: PipelineStage, val message: String) : VoiceCaptureState
}
