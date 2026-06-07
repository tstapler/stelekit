// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "CarAudioRecorder"

class CarAudioRecorder(
    private val carContext: CarContext,
) : AudioRecorder {

    private val _amplitudeFlow = MutableStateFlow(0f)
    override val amplitudeFlow: Flow<Float> = _amplitudeFlow.asStateFlow()

    private val stopFlag = AtomicBoolean(false)
    private var focusRequest: AudioFocusRequest? = null
    private var focusChangeListener: AudioManager.OnAudioFocusChangeListener? = null

    override suspend fun startRecording(): PlatformAudioFile = withContext(Dispatchers.IO) {
        stopFlag.set(false)

        val audioManager = carContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    Log.d(TAG, "Audio focus lost ($change) — stopping recording")
                    stopFlag.set(true)
                }
                else -> {}
            }
        }
        focusChangeListener = listener

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setOnAudioFocusChangeListener(listener)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            )
        }

        val outputFile = File(carContext.cacheDir, "car_voice_${System.currentTimeMillis()}.pcm")

        try {
            val carAudioRecord = CarAudioRecord.create(carContext)
            carAudioRecord.startRecording()
            val buffer = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
            FileOutputStream(outputFile).use { fos ->
                while (!stopFlag.get() && isActive) {
                    val bytesRead = carAudioRecord.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        fos.write(buffer, 0, bytesRead)
                    } else if (bytesRead < 0) {
                        break
                    }
                }
            }
            carAudioRecord.stopRecording()
        } catch (e: Exception) {
            Log.e(TAG, "CarAudioRecord error: ${e.message}", e)
        } finally {
            abandonAudioFocus(audioManager)
        }

        PlatformAudioFile(outputFile.absolutePath)
    }

    override suspend fun stopRecording() {
        stopFlag.set(true)
    }

    override suspend fun readBytes(file: PlatformAudioFile): ByteArray =
        if (file.isEmpty) ByteArray(0)
        else withContext(Dispatchers.IO) { File(file.path).readBytes() }

    override fun deleteRecording(file: PlatformAudioFile) {
        if (!file.isEmpty) File(file.path).delete()
    }

    private fun abandonAudioFocus(audioManager: AudioManager) {
        val request = focusRequest
        val listener = focusChangeListener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
            audioManager.abandonAudioFocusRequest(request)
        } else if (listener != null) {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(listener)
        }
        focusRequest = null
        focusChangeListener = null
    }
}
