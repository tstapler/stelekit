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

/**
 * Builds the per-graph [BackgroundSyncScheduler] a host runs while the app is closed. Every host
 * names its value explicitly (the field has no default); hosts with no closed-app scheduling pass
 * [None].
 */
class BackgroundSyncSchedulerFactory(
    /** Shortest interval the platform honours; the Settings picker disables anything below it. */
    val minimumIntervalMinutes: Int,
    private val build: (graphId: String) -> BackgroundSyncScheduler?,
) {
    /** Null when the platform cannot check the remote while the app is closed. */
    fun create(graphId: String): BackgroundSyncScheduler? = build(graphId)

    companion object {
        /** No closed-app scheduling (desktop, web, iOS, tests that do not exercise it). */
        val None = BackgroundSyncSchedulerFactory(minimumIntervalMinutes = 1) { null }
    }
}

/** Reports whether the OS is currently restricting background work (battery saver, restricted standby bucket). */
fun interface BackgroundRestrictionProbe {
    fun isRestricted(): Boolean
}
