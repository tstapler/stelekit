// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import kotlinx.coroutines.flow.Flow

@JvmInline
value class PlatformAudioFile(val path: String) {
    val isEmpty: Boolean get() = path.isEmpty()
}

interface AudioRecorder {
    /** Starts recording and suspends until [stopRecording] is called. Returns the output file. */
    suspend fun startRecording(): PlatformAudioFile
    /** Signals the active recording to stop. */
    suspend fun stopRecording()
    /** Reads the recorded file as bytes. Returns empty array for an empty/missing file. */
    suspend fun readBytes(file: PlatformAudioFile): ByteArray = ByteArray(0)
    /** Deletes the recorded temp file. No-op by default. */
    fun deleteRecording(file: PlatformAudioFile) = Unit
    /** Optional RMS amplitude stream for animated recording feedback. */
    val amplitudeFlow: Flow<Float>? get() = null
}

class NoOpAudioRecorder : AudioRecorder {
    override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("")
    override suspend fun stopRecording() = Unit
}
