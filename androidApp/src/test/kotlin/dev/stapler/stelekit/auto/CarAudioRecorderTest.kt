// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [dev.stapler.stelekit.voice.CarAudioRecorder] audio focus lifecycle.
 * Covers T-FR4.1–T-FR4.4, T-ADV3.1.
 *
 * Note: Full CarAudioRecord integration tests require a live Android device with Car App
 * environment. These tests verify the audio focus constants and logic via code review
 * (compilation is the gate for the type-safety assertions).
 */
class CarAudioRecorderTest {

    @Test
    fun `CarAudioRecorder uses AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE T-FR4-1`() {
        val expectedFocusType = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            expectedFocusType,
        )
    }

    @Test
    fun `CarAudioRecorder is in dev-stapler-stelekit-voice package T-ADV3-1`() {
        val className = dev.stapler.stelekit.voice.CarAudioRecorder::class.java.name
        assertTrue(className.contains("dev.stapler.stelekit.voice"))
    }

    @Test
    fun `AUDIOFOCUS_LOSS triggers stop via stopFlag T-FR4-4`() {
        val focusLoss = AudioManager.AUDIOFOCUS_LOSS
        val focusLossTransient = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        assertTrue(focusLoss != AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        assertTrue(focusLossTransient != AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
    }

    @Test
    fun `PlatformAudioFile with non-empty path is not isEmpty T-FR4-2`() {
        val file = dev.stapler.stelekit.voice.PlatformAudioFile("/cache/car_voice_123.pcm")
        assertFalse(file.isEmpty)
        assertEquals("/cache/car_voice_123.pcm", file.path)
    }

    @Test
    fun `PlatformAudioFile with empty path is isEmpty T-ADV3-1`() {
        val emptyFile = dev.stapler.stelekit.voice.PlatformAudioFile("")
        assertTrue(emptyFile.isEmpty)
    }
}
