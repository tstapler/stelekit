// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import kotlinx.coroutines.flow.Flow

/** Combines recording and transcription in a single step (e.g. Android SpeechRecognizer). */
interface DirectSpeechProvider {
    /** Records and transcribes; suspends until the user stops or silence is detected. */
    suspend fun listen(): TranscriptResult
    /** Signals an in-progress listen to stop and return results. */
    suspend fun stopListening() {}
    /** Optional RMS amplitude stream for animated feedback. */
    val amplitudeFlow: Flow<Float>? get() = null
}
