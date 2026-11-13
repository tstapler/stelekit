// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/**
 * Derives a first-read [TaskConfig] for a key from pre-existing settings. Returns null when there is
 * nothing to migrate (the store then records the marker only). Throws [SeedUnavailable] (or any
 * exception) when the legacy data cannot be read right now: the store writes no marker and retries
 * on the next read, so an unreadable source is never mistaken for "nothing to migrate".
 */
fun interface LegacyConfigSeed {
    suspend fun seed(key: TaskKey): TaskConfig?

    companion object {
        /** Seed for hosts and tests with no legacy data. */
        val None: LegacyConfigSeed = LegacyConfigSeed { null }
    }
}

/** The legacy source could not be read; the caller must not record a migration marker. */
class SeedUnavailable(message: String) : Exception(message)
