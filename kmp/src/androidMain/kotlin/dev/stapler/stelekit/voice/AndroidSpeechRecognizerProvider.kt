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
    // Set to true when the user explicitly taps stop; resets at the start of each listen().
    @Volatile private var stopRequested = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun listen(): TranscriptResult {
        if (requestMicPermission != null && !requestMicPermission()) {
            return TranscriptResult.Failure.PermissionDenied
        }
        stopRequested = false
        return listenContinuous()
    }

    /**
     * Runs a continuous listen loop: each time the recognizer stops due to silence it is
     * automatically restarted, accumulating transcript text across the gap. The loop only
     * terminates when [stopListening] sets [stopRequested] = true.
     */
    private suspend fun listenContinuous(): TranscriptResult = suspendCancellableCoroutine { cont ->
        val accumulated = StringBuilder()

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

        fun startCycle() {
            mainHandler.post {
                // Resolve immediately if stop was requested between cycles or after cancellation.
                if (!cont.isActive || stopRequested) {
                    _amplitudeFlow.value = 0f
                    if (cont.isActive) {
                        val text = accumulated.toString().trim()
                        cont.resume(if (text.isBlank()) TranscriptResult.Empty else TranscriptResult.Success(text))
                    }
                    return@post
                }

                val recognizer: SpeechRecognizer
                try {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to create SpeechRecognizer", t)
                    if (cont.isActive) cont.resume(mapError(SpeechRecognizer.ERROR_CLIENT))
                    return@post
                }
                activeRecognizer = recognizer

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onPartialResults(partialResults: Bundle?) {}

                    override fun onRmsChanged(rmsdB: Float) {
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
                        Log.d(TAG, "onResults: text=${text?.take(80)}, stopRequested=$stopRequested")
                        if (!text.isNullOrBlank()) {
                            if (accumulated.isNotEmpty()) accumulated.append(" ")
                            accumulated.append(text.trim())
                        }

                        if (stopRequested) {
                            val finalText = accumulated.toString().trim()
                            cont.resume(if (finalText.isBlank()) TranscriptResult.Empty else TranscriptResult.Success(finalText))
                        } else {
                            startCycle()
                        }
                    }

                    override fun onError(error: Int) {
                        _amplitudeFlow.value = 0f
                        activeRecognizer = null
                        recognizer.destroy()
                        if (!cont.isActive) return
                        Log.w(TAG, "onError: code=$error, stopRequested=$stopRequested")

                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                                // Silence gap — restart unless the user has tapped stop.
                                if (stopRequested) {
                                    val finalText = accumulated.toString().trim()
                                    cont.resume(if (finalText.isBlank()) TranscriptResult.Empty else TranscriptResult.Success(finalText))
                                } else {
                                    startCycle()
                                }
                            }
                            else -> cont.resume(mapError(error))
                        }
                    }
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6_000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2_000L)
                }
                try {
                    recognizer.startListening(intent)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    _amplitudeFlow.value = 0f
                    activeRecognizer = null
                    recognizer.destroy()
                    Log.w(TAG, "Failed to start speech recognition", t)
                    if (cont.isActive) cont.resume(mapError(SpeechRecognizer.ERROR_CLIENT))
                }
        }

        startCycle()
    }

    override suspend fun stopListening() {
        stopRequested = true
        withContext(Dispatchers.Main) {
            val recognizer = activeRecognizer
            if (recognizer != null) {
                // Triggers onResults() with whatever was heard; the loop sees stopRequested=true
                // and resolves the coroutine with the full accumulated transcript.
                recognizer.stopListening()
            }
            // If activeRecognizer is null we're between cycles — the next startCycle() call
            // will see stopRequested=true and resolve the coroutine directly.
        }
    }

    private fun mapError(error: Int): TranscriptResult = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> TranscriptResult.Failure.PermissionDenied
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> TranscriptResult.Failure.NetworkError
        else -> TranscriptResult.Failure.ApiError(error, "Speech recognition error (code $error)")
    }
}
