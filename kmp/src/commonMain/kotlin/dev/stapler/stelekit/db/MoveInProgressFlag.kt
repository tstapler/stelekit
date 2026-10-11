// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One coarse per-graph flag that editing, indexing, file-watching, and git sync all check as an
 * early-exit condition, so a relocate/link operation doesn't need to acquire every existing
 * platform-specific lock individually.
 *
 * In-memory only — no persistence needed. A crash mid-move already leaves a detectable staging
 * marker (Epic 3.1's `RelocationStagingDirectory`), so this flag doesn't need to survive a
 * process restart.
 *
 * Process-wide singleton by design: there is exactly one app process per device, and "is a move
 * in progress for graph X" is a fact about the whole app's current activity, not about any one
 * caller's scope.
 */
object MoveInProgressFlag {
    private val _graphIdsWithMoveInProgress = MutableStateFlow<Set<String>>(emptySet())
    val graphIdsWithMoveInProgress: StateFlow<Set<String>> = _graphIdsWithMoveInProgress.asStateFlow()

    fun isMoveInProgress(graphId: String): Boolean =
        graphId in _graphIdsWithMoveInProgress.value

    fun setMoveInProgress(graphId: String, inProgress: Boolean) {
        _graphIdsWithMoveInProgress.update { current ->
            if (inProgress) current + graphId else current - graphId
        }
    }
}
