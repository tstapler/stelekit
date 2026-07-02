// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import dev.stapler.stelekit.llm.LlmProviderAvailability
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

    /**
     * Live tri-state availability — see [mapMlKitFeatureStatus] for the
     * `FeatureStatus` -> [LlmProviderAvailability] mapping (the actual, SDK-independent, testable
     * logic). This replaces the old boolean `checkEligible()`, which collapsed `DOWNLOADABLE`/
     * `DOWNLOADING` into "eligible: true" even though [format] treated those same statuses as an
     * immediate failure.
     */
    suspend fun checkAvailability(): LlmProviderAvailability {
        val statusCode = try {
            model.checkStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "checkStatus failed", e)
            null
        }
        return mapMlKitFeatureStatus(statusCode)
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
                    LlmResult.Failure.OnDeviceUnavailable(
                        "Model is still downloading",
                        retryable = true,
                    )
                }
                else -> {
                    LlmResult.Failure.ApiError(-1, "On-device LLM not supported on this device")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            // AICore-specific error codes get a distinct, actionable message instead of the
            // generic "On-device LLM error: ..." fallback — see pitfalls.md §2.1:
            // foreground-only inference (BACKGROUND_USE_BLOCKED) and per-app quota (BUSY) are
            // both retryable, expected conditions, not bugs — so those are not logged as errors,
            // matching the pre-extraction behavior. The actual mapping lives in the pure,
            // SDK-independent mapGenAiErrorCode() (MA10) so it's testable from
            // businessTest/jvmTest without an Android SDK dependency — mirrors
            // mapMlKitFeatureStatus()'s shape for checkAvailability().
            val failure = mapGenAiErrorCode(e.errorCode, e.message)
            if (failure !is LlmResult.Failure.OnDeviceUnavailable) {
                Log.e(TAG, "On-device inference error", e)
            }
            failure
        } catch (e: Exception) {
            Log.e(TAG, "On-device inference error", e)
            LlmResult.Failure.ApiError(-1, "On-device LLM error: ${e.message}")
        }
    }
}
