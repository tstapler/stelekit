// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import dev.stapler.stelekit.voice.CarAudioRecorder
import dev.stapler.stelekit.voice.PlatformAudioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field

/**
 * Unit tests for [CarAudioRecorder] state model and interface compliance.
 * CarAudioRecord itself requires a live Android Auto host; these tests cover
 * PlatformAudioFile semantics, interface compliance, and audio focus constants.
 * Uses Java reflection — kotlin-reflect is not on the Android test classpath.
 */
class CarAudioRecorderTest {

    // ---- PlatformAudioFile contract ----

    @Test
    fun `PlatformAudioFile with non-empty path is not isEmpty`() {
        assertFalse(PlatformAudioFile("/data/cache/car_voice_123.pcm").isEmpty)
    }

    @Test
    fun `PlatformAudioFile path is preserved exactly`() {
        val path = "/data/cache/car_voice_123.pcm"
        assertEquals(path, PlatformAudioFile(path).path)
    }

    @Test
    fun `PlatformAudioFile with empty string is isEmpty`() {
        assertTrue(PlatformAudioFile("").isEmpty)
    }

    // ---- CarAudioRecorder interface compliance (Java reflection) ----

    @Test
    fun `CarAudioRecorder implements AudioRecorder interface`() {
        val names = CarAudioRecorder::class.java.interfaces.map { it.simpleName }
        assertTrue(
            "CarAudioRecorder must implement AudioRecorder, found: $names",
            names.contains("AudioRecorder"),
        )
    }

    @Test
    fun `amplitudeFlow getter exists on CarAudioRecorder`() {
        // Kotlin property `amplitudeFlow` compiles to `getAmplitudeFlow()` in JVM bytecode
        val method = CarAudioRecorder::class.java.methods.find { it.name == "getAmplitudeFlow" }
        assertNotNull("getAmplitudeFlow() must exist", method)
    }

    @Test
    fun `stopRecording has Continuation parameter (is suspend)`() {
        // Kotlin suspend functions have Continuation as their last parameter in JVM bytecode
        val m = CarAudioRecorder::class.java.declaredMethods.find { m ->
            m.name == "stopRecording" &&
                m.parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation"
        }
        assertNotNull("stopRecording must be a suspend function", m)
    }

    @Test
    fun `startRecording has Continuation parameter (is suspend)`() {
        val m = CarAudioRecorder::class.java.declaredMethods.find { m ->
            m.name == "startRecording" &&
                m.parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation"
        }
        assertNotNull("startRecording must be a suspend function", m)
    }

    // ---- stopFlag field type check ----

    @Test
    fun `stopFlag field is an AtomicBoolean`() {
        val field: Field = CarAudioRecorder::class.java.getDeclaredField("stopFlag")
        field.isAccessible = true
        assertEquals(
            "stopFlag must be AtomicBoolean",
            java.util.concurrent.atomic.AtomicBoolean::class.java,
            field.type,
        )
    }

    // ---- Audio focus constants ----

    @Test
    fun `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE value is 4`() {
        assertEquals(4, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
    }

    @Test
    fun `AUDIOFOCUS_LOSS value is negative`() {
        assertTrue(android.media.AudioManager.AUDIOFOCUS_LOSS < 0)
    }

    @Test
    fun `AUDIOFOCUS_LOSS_TRANSIENT value is negative`() {
        assertTrue(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT < 0)
    }
}
