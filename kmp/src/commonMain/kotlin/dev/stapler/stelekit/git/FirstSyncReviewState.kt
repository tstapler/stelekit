// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.GitConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a first-sync review is owed for one graph: after a branch repair, or for any configured
 * `(remote, branch)` without a confirmed manual sync (e.g. the first run after an upgrade). Drives the
 * "Review first sync" badge; [GitSyncService] owns one per graph.
 */
class FirstSyncReviewState(private val confirmation: FirstSyncConfirmation?, private val graphId: String) {
    private val _pending = MutableStateFlow(confirmation?.isReviewPending(graphId) ?: false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    /** Re-reads the persisted flags against [config]; a changed branch is unconfirmed again. */
    fun refresh(config: GitConfig) {
        val c = confirmation ?: return
        _pending.value = c.isReviewPending(graphId) || !c.isConfirmed(graphId, config.remoteName, config.remoteBranch)
    }

    /** After a branch repair only the explicit pending flag counts until the next [refresh]. */
    fun onRepaired() {
        _pending.value = confirmation?.isReviewPending(graphId) ?: false
    }

    /** A manual sync succeeded and recorded the confirmation. */
    fun onConfirmed() {
        _pending.value = false
    }
}
