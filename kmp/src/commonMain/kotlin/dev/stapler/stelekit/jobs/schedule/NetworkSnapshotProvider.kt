// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/** Live connectivity at one instant. [isMetered] is null when the platform cannot say (fail closed for `Unmetered`). */
data class NetworkSnapshot(val online: Boolean, val isMetered: Boolean?)

/**
 * The single source of live connectivity for a worker running with no UI state (cold process) and for
 * the LFS metered read. A throwing provider is treated by callers as an unknown network, never as proceed.
 */
fun interface NetworkSnapshotProvider {
    suspend fun snapshot(): NetworkSnapshot
}
