// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.InetAddress

/**
 * JVM (Desktop) implementation of [NetworkMonitor].
 *
 * [isOnline] performs a synchronous reachability check to 8.8.8.8 with a 2-second timeout.
 * [observeConnectivity] polls every 30 seconds, emitting the current connectivity state.
 *
 * Note: DNS-based connectivity detection may fail in environments with split-DNS or
 * restrictive firewalls. This is sufficient for a desktop app on a typical workstation.
 */
actual class NetworkMonitor actual constructor() {

    actual val isOnline: Boolean
        get() = try {
            InetAddress.getByName("8.8.8.8").isReachable(2000)
        } catch (_: Exception) {
            false
        }

    actual fun observeConnectivity(): Flow<Boolean> = flow {
        while (true) {
            emit(isOnline)
            delay(30_000L)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
}
