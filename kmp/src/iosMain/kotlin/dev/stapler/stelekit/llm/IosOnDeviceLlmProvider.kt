// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import dev.stapler.stelekit.voice.IosOnDeviceLlmFormatterProvider
import dev.stapler.stelekit.voice.LlmFormatterProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import shim.foundationmodels.FoundationModelsShim
import kotlin.coroutines.resume

/**
 * Story 5.5c (Epic 5, llm-service): iOS on-device [LlmProvider], backed by the cinterop-bound
 * `FoundationModelsShim` (Apple Intelligence / FoundationModels framework, iOS 26+).
 *
 * Unlike Android ([platformOnDeviceLlmProvider] there returns `null` when ML Kit's SDK fails to
 * initialize), this provider is **always non-null** — per ADR-013, unavailability on iOS is
 * expressed through [checkAvailability] (device/OS/region/enablement state), not through
 * construction failure. The shim itself has no "SDK failed to init" case; `FoundationModels`
 * either exists in the SDK the app was compiled against (`#if canImport(FoundationModels)`
 * in the shim) or the shim reports [LlmProviderAvailability.Unavailable] at every check.
 *
 * COULD NOT BE COMPILED OR RUN IN THIS ENVIRONMENT — see [IosOnDeviceLlmFormatterProvider]'s
 * doc comment and `iosApp/README.md` for exactly what could and couldn't be verified.
 */
class IosOnDeviceLlmProvider(
    private val shim: FoundationModelsShim = FoundationModelsShim(),
) : LlmProvider {
    override val id: String = "ios-ondevice"
    override val displayName: String = "On-device (Apple Intelligence)"
    override val kind: LlmProviderKind = LlmProviderKind.ON_DEVICE

    // Apple's ~3B-parameter on-device model has the same short-output-ceiling carve-out as
    // Android's on-device ML Kit provider (ADR-013 "Consequences" — negative risks) — excluded
    // from features like graph-edit synthesis that need long-form output.
    override val supportsLongFormOutput: Boolean = false

    override val formatter: LlmFormatterProvider = IosOnDeviceLlmFormatterProvider(shim)

    /**
     * Live, uncached availability check — per ADR-013, region/eligibility is a moving target
     * (ongoing EU DMA-related restrictions) and must be re-evaluated fresh every call, never
     * cached across app sessions/versions. Delegates the actual code-to-tri-state mapping to
     * [mapShimCodeToAvailability] (Task 5.5d's extracted pure function — the part of this class
     * that *is* covered by `businessTest`, via `IosAvailabilityMappingTest`).
     */
    override suspend fun checkAvailability(): LlmProviderAvailability =
        suspendCancellableCoroutine { continuation ->
            // TODO(Epic 5, manual verification): confirm the generated Kotlin binding name for
            // FoundationModelsShim's `@objc(checkAvailabilityWithCompletion:)` method matches
            // this call once cinterop actually runs against a real Xcode build.
            shim.checkAvailabilityWithCompletion { code, detail ->
                val availability = mapShimCodeToAvailability(code.toInt(), detail)
                if (continuation.isActive) {
                    continuation.resume(availability)
                }
            }
        }
}
