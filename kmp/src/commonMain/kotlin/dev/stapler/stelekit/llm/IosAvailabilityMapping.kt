// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

/**
 * Pure mapping from the iOS `FoundationModelsShim.checkAvailability(completion:)` result codes
 * (Story 5.3's Swift shim, `project_plans/llm-service/implementation/plan.md` Epic 5) to the
 * shared [LlmProviderAvailability] tri-state.
 *
 * Deliberately extracted as a standalone `(Int, String?) -> LlmProviderAvailability` function,
 * independent of the actual cinterop call, so it can be exercised in `businessTest`/`jvmTest`
 * without any iOS SDK/cinterop dependency (Task 5.5d) — the cinterop-calling code itself
 * (`kmp/src/iosMain/kotlin/dev/stapler/stelekit/llm/IosOnDeviceLlmProvider.kt`) cannot run
 * outside a real iOS/Xcode toolchain and is therefore only manually verifiable (Task 5.5e).
 *
 * Shim codes, per Story 5.3's acceptance criteria:
 *   0 = available
 *   1 = deviceNotEligible
 *   2 = appleIntelligenceNotEnabled
 *   3 = modelNotReady
 *   4 = other
 *
 * Lives in `commonMain` (not `iosMain`) purely so `businessTest` (which runs on the JVM target,
 * per `kmp/build.gradle.kts`'s `businessTest.dependsOn(commonTest)` / `jvmTest.dependsOn
 * (businessTest)` wiring) can compile and execute it directly — mirrors the same pattern noted
 * for Android's `MlKitAvailabilityMappingTest` (Epic 4, Task 4.1d).
 */
fun mapShimCodeToAvailability(code: Int, detail: String?): LlmProviderAvailability = when (code) {
    0 -> LlmProviderAvailability.Available
    1 -> LlmProviderAvailability.Unavailable(
        reason = "This device doesn't support on-device AI",
        retryable = false,
    )
    2 -> LlmProviderAvailability.Unavailable(
        reason = "Turn on Apple Intelligence in Settings to use on-device AI",
        retryable = false,
    )
    3 -> LlmProviderAvailability.Preparing(
        detail = "The on-device model is still downloading",
    )
    else -> LlmProviderAvailability.Unavailable(
        reason = detail ?: "Unknown",
        retryable = true,
    )
}
