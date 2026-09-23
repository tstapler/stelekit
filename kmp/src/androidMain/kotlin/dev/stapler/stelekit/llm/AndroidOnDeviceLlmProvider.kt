// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.MlKitLlmFormatterProvider

/**
 * Android on-device [LlmProvider], backed by ML Kit Prompt API (Gemini Nano via AICore).
 *
 * [supportsLongFormOutput] is hard-`false` — the on-device model's ~256-token output ceiling
 * (see [MlKitLlmFormatterProvider]'s class doc) excludes it from features like graph-edit
 * synthesis (pitfalls.md §2.1/plan.md Story 1.2).
 */
class AndroidOnDeviceLlmProvider(
    private val delegate: MlKitLlmFormatterProvider,
) : LlmProvider {
    override val id: String = "android-ondevice"
    override val displayName: String = "On-device (Gemini Nano)"
    override val kind: LlmProviderKind = LlmProviderKind.ON_DEVICE
    override val supportsLongFormOutput: Boolean = false
    override val formatter: LlmFormatterProvider = delegate

    override suspend fun checkAvailability(): LlmProviderAvailability = delegate.checkAvailability()
}
