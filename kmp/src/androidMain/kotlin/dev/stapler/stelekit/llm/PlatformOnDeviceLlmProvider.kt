// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import dev.stapler.stelekit.voice.MlKitLlmFormatterProvider

// Real ML Kit-backed implementation (Epic 4 Story 4.2). Returns null when the ML Kit library
// fails to initialise (e.g. unsupported device) — mirrors MlKitLlmFormatterProvider.create()'s
// existing "capability not available" convention.
actual fun platformOnDeviceLlmProvider(): LlmProvider? =
    MlKitLlmFormatterProvider.create()?.let { AndroidOnDeviceLlmProvider(it) }
