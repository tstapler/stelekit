// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "AndroidSpeechRecognizer"

class AndroidSpeechRecognizerProvider(
    private val context: Context,
    private val requestMicPermission: (suspend () -> Boolean)? = null,
) : DirectSpeechProvider {

    companion object {
        fun isAvailable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context)
    }

    private val _amplitudeFlow = MutableStateFlow(0f)
    override val amplitudeFlow: Flow<Float> = _amplitudeFlow.asStateFlow()

    @Volatile private var activeRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun listen(): TranscriptResult {
        if (requestMicPermission != null && !requestMicPermission()) {
            return TranscriptResult.Failure.PermissionDenied
        }
        return listenInternal()
    }

    private suspend fun listenInternal(): TranscriptResult = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            mainHandler.post {
                activeRecognizer?.let {
                    it.cancel()
                    it.destroy()
                    activeRecognizer = null
                }
                _amplitudeFlow.value = 0f
            }
        }

        mainHandler.post {
            var recognizer: SpeechRecognizer? = null
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                activeRecognizer = recognizer

                // Guard against cancellation that fired before this post ran
                if (!cont.isActive) {
                    recognizer.destroy()
                    activeRecognizer = null
                    return@post
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onPartialResults(partialResults: Bundle?) {}

                    override fun onRmsChanged(rmsdB: Float) {
                        // Map roughly -2..10 dB → 0..1
                        _amplitudeFlow.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    }

                    override fun onResults(results: Bundle?) {
                        _amplitudeFlow.value = 0f
                        activeRecognizer = null
                        recognizer.destroy()
                        if (!cont.isActive) return
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        Log.d(TAG, "onResults: text=${text?.take(80)}")
                        if (text.isNullOrBlank()) cont.resume(TranscriptResult.Empty)
                        else cont.resume(TranscriptResult.Success(text))
                    }

                    override fun onError(error: Int) {
                        _amplitudeFlow.value = 0f
                        activeRecognizer = null
                        recognizer.destroy()
                        if (!cont.isActive) return
                        Log.w(TAG, "onError: code=$error")
                        cont.resume(mapError(error))
                    }
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
                }
                recognizer.startListening(intent)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _amplitudeFlow.value = 0f
                activeRecognizer = null
                recognizer?.destroy()
                Log.w(TAG, "Failed to start speech recognition", t)
                if (cont.isActive) {
                    cont.resume(mapError(SpeechRecognizer.ERROR_CLIENT))
                }
            }
        }
    }

    override suspend fun stopListening() {
        withContext(Dispatchers.Main) {
            activeRecognizer?.stopListening()
        }
    }

    private fun mapError(error: Int): TranscriptResult = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> TranscriptResult.Empty
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> TranscriptResult.Failure.PermissionDenied
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> TranscriptResult.Failure.NetworkError
        else -> TranscriptResult.Failure.ApiError(error, "Speech recognition error (code $error)")
    }
}
