// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import android.content.Context
import dev.stapler.stelekit.platform.NetworkMonitor

/**
 * The one [NetworkSnapshotProvider] over `ConnectivityManager`, built from a [Context] alone so a cold
 * worker needs no UI state. Delegates to [NetworkMonitor] so its `SecurityException -> isMetered == null`
 * behaviour is reused rather than duplicated.
 */
class AndroidNetworkSnapshotProvider(context: Context) : NetworkSnapshotProvider {
    private val monitor: NetworkMonitor

    init {
        NetworkMonitor.init(context)
        monitor = NetworkMonitor()
    }

    override suspend fun snapshot(): NetworkSnapshot = NetworkSnapshot(online = monitor.isOnline, isMetered = monitor.isMetered)
}
