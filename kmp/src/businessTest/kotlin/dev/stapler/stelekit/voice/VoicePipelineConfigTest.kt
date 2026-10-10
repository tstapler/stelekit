// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers GAP-002 (`project_plans/rich-editing-experience/implementation/gap-backlog.md`):
 * [VoiceCaptureButton][dev.stapler.stelekit.ui.components.VoiceCaptureButton] must be able to
 * tell, up front, whether the wired pipeline can actually capture audio, so it can render an
 * honest "not available" affordance instead of a fully-interactive-looking control that
 * silently records nothing on platforms with no real [AudioRecorder].
 */
class VoicePipelineConfigTest {

    private class FakeAudioRecorder : AudioRecorder {
        override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/real.m4a")
        override suspend fun stopRecording() = Unit
    }

    private class FakeDirectSpeechProvider : DirectSpeechProvider {
        override suspend fun listen(): TranscriptResult = TranscriptResult.Success("hi")
    }

    @Test
    fun `default config (NoOpAudioRecorder, no directSpeechProvider) is not supported`() {
        val config = VoicePipelineConfig()
        assertFalse(config.isSupported)
    }

    @Test
    fun `config with a real AudioRecorder is supported`() {
        val config = VoicePipelineConfig(audioRecorder = FakeAudioRecorder())
        assertTrue(config.isSupported)
    }

    @Test
    fun `config with a DirectSpeechProvider is supported even with NoOpAudioRecorder`() {
        val config = VoicePipelineConfig(directSpeechProvider = FakeDirectSpeechProvider())
        assertTrue(config.isSupported)
    }

    @Test
    fun `explicit NoOpAudioRecorder and no directSpeechProvider is not supported`() {
        val config = VoicePipelineConfig(audioRecorder = NoOpAudioRecorder())
        assertFalse(config.isSupported)
    }
}
