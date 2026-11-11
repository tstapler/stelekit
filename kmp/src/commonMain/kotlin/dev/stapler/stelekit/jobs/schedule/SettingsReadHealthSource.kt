// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/** The "a background process could not read task settings" marker, readable from common UI code. */
interface SettingsReadHealthSource {
    /** Epoch millis of the last unreadable-settings failure, or null if none since the last [clear]. */
    fun lastFailureMs(): Long?

    fun clear()
}
