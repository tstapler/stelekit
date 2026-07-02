// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

/**
 * Platform-specific on-device [LlmProvider], or `null` if this platform has no on-device
 * inference capability (or the capability is currently unavailable at construction time).
 * Mirrors the existing `expect class CredentialStore()` / `MlKitLlmFormatterProvider.create()
 * -> null` "capability not available" convention already established in this codebase.
 */
expect fun platformOnDeviceLlmProvider(): LlmProvider?
