// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Tracks whether a [GitSyncService.sync] call is currently in flight.
 * Used by the relocate quiesce sequence (Phase 3) to detect "a JGit commit/merge/push is
 * currently running" so a relocate never copies `.git` mid-operation.
 *
 * Modeled on [EditLock] — same counter/awaitIdle shape, different source of busy-ness.
 *
 * Owns its own CoroutineScope — never accept rememberCoroutineScope().
 */
class GitSyncBusyCounter {
    private val busyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _busyCount = MutableStateFlow(0)

    fun begin() {
        _busyCount.update { it + 1 }
    }

    fun end() {
        _busyCount.update { maxOf(it - 1, 0) }
    }

    /**
     * Suspends until no [GitSyncService.sync] calls are in flight.
     * Called by the relocate quiesce sequence before moving a graph's `.git` directory.
     */
    suspend fun awaitIdle() {
        _busyCount.first { it == 0 }
    }

    val isBusy: StateFlow<Boolean> = _busyCount
        .map { it > 0 }
        .stateIn(busyScope, SharingStarted.Eagerly, false)
}
