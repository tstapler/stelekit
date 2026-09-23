// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import kotlinx.coroutines.suspendCancellableCoroutine
import shim.foundationmodels.FoundationModelsShim
import kotlin.coroutines.resume

/**
 * Story 5.5b (Epic 5, llm-service): [LlmFormatterProvider] backed by the cinterop-generated
 * [FoundationModelsShim] binding (Story 5.4's `.def` file:
 * `kmp/src/nativeInterop/cinterop/FoundationModelsShim.def`). Wraps the shim's
 * completion-handler `format(transcript:systemPrompt:completion:)` in
 * [suspendCancellableCoroutine], per ADR-013's documented bridging pattern — Swift structured
 * concurrency does not bridge to Obj-C/cinterop, so the Swift shim exposes a completion handler
 * instead of `async`/`await`, and this is the Kotlin-side re-wrap back into a `suspend fun`.
 *
 * ============================================================================================
 * COULD NOT BE COMPILED OR RUN IN THIS ENVIRONMENT (Linux sandbox, no Xcode/macOS/iOS SDK, no
 * cinterop toolchain — `./gradlew :kmp:compileKotlinIosSimulatorArm64` and the underlying
 * `cinteropFoundationModelsShimIosSimulatorArm64` task are both SKIPPED on this host: Gradle
 * logs "cross compilation to target 'iosSimulatorArm64' has been disabled because it contains
 * cinterops: 'FoundationModelsShim' which cannot be processed on host 'linux_x64'"). This means
 * the `shim.foundationmodels.FoundationModelsShim` import below, and every call against it, is
 * UNVERIFIED — the generated Kotlin binding's actual method name/signature was never produced.
 * ============================================================================================
 *
 * Known uncertainty a developer with Xcode MUST verify (see iosApp/README.md's "Known
 * unverified assumptions" section):
 *   - The generated Kotlin method name for the shim's
 *     `@objc(formatWithTranscript:systemPrompt:completion:)`-annotated Swift method is expected
 *     to import as `formatWithTranscript(transcript:systemPrompt:completion:)` (Kotlin/Native's
 *     Obj-C interop generally preserves the Objective-C selector's segments as named
 *     parameters) — confirm this once cinterop actually runs, and rename the call below if
 *     different.
 *   - Swift `Int` bridges to Objective-C `NSInteger`, which Kotlin/Native cinterop typically
 *     imports as `kotlin.Long` (not `kotlin.Int`) — hence `.toInt()` calls below. Confirm this
 *     is actually what gets generated.
 *
 * Task 5.5e (MANUAL VERIFICATION ONLY): exercise this class end-to-end (Kotlin → cinterop →
 * Swift shim → FoundationModels) on physical/simulator hardware. Not performed here.
 */
class IosOnDeviceLlmFormatterProvider(
    private val shim: FoundationModelsShim = FoundationModelsShim(),
) : LlmFormatterProvider {

    override suspend fun format(transcript: String, systemPrompt: String): LlmResult =
        suspendCancellableCoroutine { continuation ->
            // TODO(Epic 5, manual verification): confirm the generated Kotlin binding name for
            // FoundationModelsShim's `@objc(formatWithTranscript:systemPrompt:completion:)`
            // method matches this call once cinterop actually runs against a real Xcode build.
            shim.formatWithTranscript(
                transcript = transcript,
                systemPrompt = systemPrompt,
            ) { text, code, detail ->
                val intCode = code.toInt()
                val result = when (intCode) {
                    0 -> LlmResult.Success(
                        formattedText = text ?: "",
                        isLikelyTruncated = LlmProviderSupport.detectTruncation(text ?: ""),
                    )
                    1 -> LlmResult.Failure.ContentRejected(
                        detail ?: "Content was rejected by on-device safety guardrails",
                    )
                    else -> LlmResult.Failure.ApiError(
                        intCode,
                        detail ?: "On-device (Apple Intelligence) formatting failed",
                    )
                }
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }
}
