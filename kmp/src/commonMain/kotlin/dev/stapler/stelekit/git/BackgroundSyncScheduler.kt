// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/**
 * Platform-agnostic interface for scheduling periodic background git sync.
 */
interface BackgroundSyncScheduler {
    /** Schedule periodic sync at the given interval (minimum 15 minutes on Android). */
    fun schedule(intervalMinutes: Int)

    /** Cancel any scheduled sync. */
    fun cancel()
}
