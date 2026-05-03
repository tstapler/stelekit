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
 * Tracks whether any blocks are currently being edited.
 * Used by GitSyncService to wait until editing is idle before merging.
 *
 * Owns its own CoroutineScope — never accept rememberCoroutineScope().
 */
class EditLock {
    private val editLockScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _editingCount = MutableStateFlow(0)

    fun beginEdit() {
        _editingCount.update { it + 1 }
    }

    fun endEdit() {
        _editingCount.update { maxOf(it - 1, 0) }
    }

    /**
     * Suspends until no blocks are in edit mode.
     * Called by GitSyncService before merge.
     */
    suspend fun awaitIdle() {
        _editingCount.first { it == 0 }
    }

    val isEditing: StateFlow<Boolean> = _editingCount
        .map { it > 0 }
        .stateIn(editLockScope, SharingStarted.Eagerly, false)
}
