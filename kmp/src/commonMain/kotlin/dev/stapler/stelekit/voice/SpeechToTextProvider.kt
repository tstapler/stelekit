// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

sealed interface TranscriptResult {
    data class Success(val text: String) : TranscriptResult
    data object Empty : TranscriptResult
    sealed interface Failure : TranscriptResult {
        data class ApiError(val code: Int, val message: String) : Failure
        data object NetworkError : Failure
        data object PermissionDenied : Failure
    }
}

fun interface SpeechToTextProvider {
    suspend fun transcribe(audioData: ByteArray): TranscriptResult
}

class NoOpSpeechToTextProvider : SpeechToTextProvider {
    override suspend fun transcribe(audioData: ByteArray): TranscriptResult = TranscriptResult.Empty
}
