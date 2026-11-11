// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet

/**
 * Tracks whether any blocks are currently being edited.
 * Used by GitSyncService to wait until editing is idle before merging.
 */
class EditLock {
    private val _editingCount = MutableStateFlow(0)

    /**
     * Suspends until no blocks are in edit mode.
     * Called by GitSyncService before merge.
     */
    suspend fun awaitIdle() {
        _editingCount.first { it == 0 }
    }

    // Updated synchronously with the count: a stateIn-derived flag lags, so a scheduled tick right
    // after beginEdit() could read false and start a sync that then blocks in awaitIdle().
    private val _isEditing = MutableStateFlow(false)

    fun beginEdit() {
        val count = _editingCount.updateAndGet { it + 1 }
        _isEditing.value = count > 0
    }

    fun endEdit() {
        val count = _editingCount.updateAndGet { maxOf(it - 1, 0) }
        _isEditing.value = count > 0
    }

    val isEditing: StateFlow<Boolean> = _isEditing.asStateFlow()
}
