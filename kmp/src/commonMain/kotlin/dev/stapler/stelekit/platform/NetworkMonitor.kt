// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic network connectivity monitor.
 * Used by GitSyncService to skip sync operations when offline.
 */
expect class NetworkMonitor() {
    val isOnline: Boolean

    /** True on a metered connection, false on an unmetered one, null where the platform cannot tell (desktop, iOS, web). */
    val isMetered: Boolean?
    fun observeConnectivity(): Flow<Boolean>
}
