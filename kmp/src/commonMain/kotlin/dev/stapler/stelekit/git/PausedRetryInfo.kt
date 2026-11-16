// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/**
 * The automatic retry pending after a paused sync (ADR-001 decision 14); populated by the service in Task 1.7c.
 * [tryNumber] is 1 or 2 of [MAX_TRIES]; "Retry now" never moves it.
 */
data class PausedRetryInfo(val nextRetryAtMs: Long, val tryNumber: Int) {
    companion object {
        const val MAX_TRIES = 2
    }
}
