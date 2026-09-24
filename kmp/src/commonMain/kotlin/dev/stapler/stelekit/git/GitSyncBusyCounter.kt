// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet

/**
 * Tracks whether a [GitSyncService.sync] call is currently in flight.
 * Used by the relocate quiesce sequence (Phase 3) to detect "a JGit commit/merge/push is
 * currently running" so a relocate never copies `.git` mid-operation.
 *
 * Modeled on [EditLock] — same counter/awaitIdle shape, different source of busy-ness.
 */
class GitSyncBusyCounter {
    private val _busyCount = MutableStateFlow(0)

    // Updated synchronously alongside _busyCount in begin()/end() rather than derived via
    // .map { it > 0 }.stateIn(scope, ...) on a separate CoroutineScope/dispatcher — that
    // derivation lags asynchronously behind _busyCount's own update, so a caller reading
    // isBusy.value immediately after begin() returns can observe a stale `false` (confirmed:
    // GitSyncBusyCounterSharedWiringTest's assertion right after sync() reaches its first
    // suspension point is timing-dependent on the collector coroutine having already run).
    private val _isBusy = MutableStateFlow(false)

    fun begin() {
        val count = _busyCount.updateAndGet { it + 1 }
        _isBusy.value = count > 0
    }

    fun end() {
        val count = _busyCount.updateAndGet { maxOf(it - 1, 0) }
        _isBusy.value = count > 0
    }

    /**
     * Suspends until no [GitSyncService.sync] calls are in flight.
     * Called by the relocate quiesce sequence before moving a graph's `.git` directory.
     */
    suspend fun awaitIdle() {
        _busyCount.first { it == 0 }
    }

    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()
}
