// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import platform.Network.NWPathMonitor
import platform.Network.NWPathStatus
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_get_status
import platform.darwin.dispatch_queue_create
import platform.darwin.DISPATCH_QUEUE_SERIAL

/**
 * iOS implementation of [NetworkMonitor] using NWPathMonitor from the Network framework.
 */
actual class NetworkMonitor actual constructor() {

    private val monitor = nw_path_monitor_create()
    private val queue = dispatch_queue_create("dev.stapler.stelekit.networkMonitor", DISPATCH_QUEUE_SERIAL)

    @Volatile
    private var _isOnline: Boolean = true

    init {
        nw_path_monitor_set_queue(monitor, queue)
        nw_path_monitor_set_update_handler(monitor) { path ->
            _isOnline = nw_path_get_status(path) == NWPathStatus.nw_path_status_satisfied
        }
        nw_path_monitor_start(monitor)
    }

    actual val isOnline: Boolean get() = _isOnline

    actual fun observeConnectivity(): Flow<Boolean> = callbackFlow {
        val localMonitor = nw_path_monitor_create()
        val localQueue = dispatch_queue_create("dev.stapler.stelekit.nm.observer", DISPATCH_QUEUE_SERIAL)

        nw_path_monitor_set_queue(localMonitor, localQueue)
        nw_path_monitor_set_update_handler(localMonitor) { path ->
            val online = nw_path_get_status(path) == NWPathStatus.nw_path_status_satisfied
            trySend(online)
        }
        nw_path_monitor_start(localMonitor)

        awaitClose { nw_path_monitor_cancel(localMonitor) }
    }.distinctUntilChanged()
}
