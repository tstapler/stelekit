// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import dev.stapler.stelekit.voice.LlmFormatterProvider

/**
 * Thin metadata + availability wrapper around the existing [LlmFormatterProvider] contract.
 * [LlmFormatterProvider] itself is unchanged — every existing implementation
 * (`ClaudeLlmFormatterProvider`, `OpenAiLlmFormatterProvider`, `MlKitLlmFormatterProvider`)
 * and every existing test fake continues to compile and work with zero call-site churn.
 */
interface LlmProvider {
    /** Stable identity: "anthropic", "openai", "gemini", "android-ondevice", "ios-ondevice",
     * "custom:<uuid>" for user-added generic OpenAI-compatible instances. */
    val id: String
    val displayName: String
    val kind: LlmProviderKind
    val formatter: LlmFormatterProvider

    /**
     * Whether this provider can produce long-form output. On-device providers (Android
     * ML Kit / iOS Foundation Models) override this to `false` — their ~256-token-class
     * output ceiling excludes them from features like graph-edit synthesis.
     */
    val supportsLongFormOutput: Boolean get() = true

    /**
     * Live availability check — always re-evaluated, never a cached snapshot. On-device
     * eligibility can flip mid-session (background model download); a remote API key can
     * be rotated or revoked.
     */
    suspend fun checkAvailability(): LlmProviderAvailability
}
