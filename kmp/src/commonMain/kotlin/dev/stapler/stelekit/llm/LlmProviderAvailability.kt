// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

/**
 * Tri-state availability for an [LlmProvider]. Deliberately not a boolean — collapsing
 * "eligible but still downloading/initializing" into "available" caused
 * `MlKitLlmFormatterProvider.checkEligible()` and `format()` to disagree in production
 * (checkEligible reported DOWNLOADABLE/DOWNLOADING as eligible, but format() treated those
 * same states as a failure). Consumers that need "usable right now" must check for
 * [Available] specifically; [Preparing] means "retry later, no user action needed."
 */
sealed interface LlmProviderAvailability {
    data object Available : LlmProviderAvailability

    /** Downloading or initializing — will likely become [Available] without user action. */
    data class Preparing(val detail: String? = null) : LlmProviderAvailability

    /**
     * Unusable right now. Permanent unless [retryable] — e.g. "unsupported hardware" (not
     * retryable) vs. "still initializing after reset" (retryable).
     */
    data class Unavailable(val reason: String, val retryable: Boolean = false) : LlmProviderAvailability
}
