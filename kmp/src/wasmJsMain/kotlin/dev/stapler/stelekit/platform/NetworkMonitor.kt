// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

actual class NetworkMonitor actual constructor() {
    actual val isOnline: Boolean get() = true
    actual fun observeConnectivity(): Flow<Boolean> = flowOf(true)
}
