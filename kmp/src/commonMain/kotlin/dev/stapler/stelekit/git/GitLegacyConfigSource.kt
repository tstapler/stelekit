// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/** Result of reading a graph's pre-existing git config for the lazy scheduling migration. */
sealed interface LegacyGitConfigResult {
    data class Found(val hasRemote: Boolean, val pollIntervalMinutes: Int) : LegacyGitConfigResult

    /** The config could not be read (DB unopenable or not yet open); never means "no remote". */
    data object Unavailable : LegacyGitConfigResult
}

/**
 * Reads a graph's git config (remote presence, `poll_interval_minutes`). "Cannot read" is
 * [LegacyGitConfigResult.Unavailable] or a throw, never `Found(hasRemote = false)`: mistaking an
 * unreadable DB for "no remote" would permanently drop the user's sync.
 */
fun interface GitLegacyConfigSource {
    suspend fun read(graphId: String): LegacyGitConfigResult
}
