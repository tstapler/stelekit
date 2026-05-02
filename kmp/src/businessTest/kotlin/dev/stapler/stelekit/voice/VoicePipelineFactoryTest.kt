// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VoicePipelineFactoryTest {

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
    }

    private val fakeProvider = object : DirectSpeechProvider {
        override suspend fun listen(): TranscriptResult = TranscriptResult.Empty
    }

    // --- directSpeechProvider pass-through ---

    @Test
    fun `buildVoicePipeline passes non-null directSpeechProvider through to config`() {
        val config = buildVoicePipeline(NoOpAudioRecorder(), VoiceSettings(MapSettings()), fakeProvider)
        assertSame(fakeProvider, config.directSpeechProvider)
    }

    @Test
    fun `buildVoicePipeline passes null directSpeechProvider through to config`() {
        val config = buildVoicePipeline(NoOpAudioRecorder(), VoiceSettings(MapSettings()), null)
        assertNull(config.directSpeechProvider)
    }

    // --- sttProvider error when no Whisper key and no directSpeechProvider ---

    @Test
    fun `sttProvider returns ApiError mentioning Whisper when no key is configured`() = runTest {
        val config = buildVoicePipeline(NoOpAudioRecorder(), VoiceSettings(MapSettings()))
        val result = config.sttProvider.transcribe(ByteArray(0))
        assertIs<TranscriptResult.Failure.ApiError>(result)
        assertTrue(
            result.message.contains("Whisper", ignoreCase = true),
            "Expected error message to mention Whisper, got: ${result.message}",
        )
    }

    // --- directSpeechProvider is used by default when getUseDeviceStt() is true ---

    @Test
    fun `config has null directSpeechProvider when none is provided regardless of settings`() {
        // The factory receives the already-gated provider from MainActivity.
        // This test verifies the factory itself does not inject a provider.
        val settings = VoiceSettings(MapSettings())  // useDeviceStt=true by default
        val config = buildVoicePipeline(NoOpAudioRecorder(), settings, directSpeechProvider = null)
        assertNull(config.directSpeechProvider)
    }

    @Test
    fun `getUseDeviceStt true by default means MainActivity condition passes deviceSttProvider`() {
        // Validates the contract between VoiceSettings and MainActivity's gating logic:
        //   if (deviceSttAvailable && voiceSettings.getUseDeviceStt()) deviceSttProvider else null
        // With the default true, a device where deviceSttAvailable=true gets the provider.
        val settings = VoiceSettings(MapSettings())
        val deviceSttAvailable = true
        val gatedProvider: DirectSpeechProvider? = if (deviceSttAvailable && settings.getUseDeviceStt()) fakeProvider else null
        assertNotNull(gatedProvider, "Expected on-device STT to be used by default when device supports it")
    }
}
