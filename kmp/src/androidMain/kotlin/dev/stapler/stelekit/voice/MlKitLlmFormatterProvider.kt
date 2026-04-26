// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CancellationException

private const val TAG = "MlKitLlmFormatter"

/**
 * On-device LLM formatter backed by ML Kit Prompt API (Gemini Nano via AICore).
 *
 * Supported devices: Pixel 9+ and major OEM flagships with AICore (Samsung S25, etc.).
 * Output hard-capped at 256 tokens by the on-device model — suitable for short voice notes.
 * API status: beta (com.google.mlkit:genai-prompt:1.0.0-beta2).
 */
class MlKitLlmFormatterProvider private constructor(
    private val model: GenerativeModel,
) : LlmFormatterProvider {

    companion object {
        /** Creates the provider; returns null if the ML Kit library fails to initialise. */
        fun create(): MlKitLlmFormatterProvider? = runCatching {
            MlKitLlmFormatterProvider(Generation.getClient())
        }.getOrElse { e ->
            Log.w(TAG, "Failed to create GenerativeModel", e)
            null
        }
    }

    /** Returns true when the device supports on-device inference (model available or will download). */
    suspend fun checkEligible(): Boolean = runCatching {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE,
            FeatureStatus.DOWNLOADABLE,
            FeatureStatus.DOWNLOADING -> true
            else -> false
        }
    }.getOrElse { e ->
        Log.w(TAG, "checkStatus failed", e)
        false
    }

    override suspend fun format(transcript: String, systemPrompt: String): LlmResult {
        return try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> {
                    Log.d(TAG, "Running on-device inference (${transcript.length} chars input)")
                    val response = model.generateContent(systemPrompt)
                    val text = response.candidates.firstOrNull()?.text?.trim()
                    if (text.isNullOrBlank()) {
                        LlmResult.Failure.ApiError(-1, "Empty response from on-device model")
                    } else {
                        Log.d(TAG, "On-device inference complete (${text.length} chars output)")
                        LlmResult.Success(text, LlmProviderSupport.detectTruncation(text))
                    }
                }
                FeatureStatus.DOWNLOADABLE,
                FeatureStatus.DOWNLOADING -> {
                    // AICore downloads the model in the background automatically.
                    // Blocking here would take several minutes — return a friendly retry message.
                    LlmResult.Failure.ApiError(
                        -1,
                        "On-device model is downloading — try again in a few minutes"
                    )
                }
                else -> {
                    LlmResult.Failure.ApiError(-1, "On-device LLM not supported on this device")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "On-device inference error", e)
            LlmResult.Failure.ApiError(-1, "On-device LLM error: ${e.message}")
        }
    }
}
