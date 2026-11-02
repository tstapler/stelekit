// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.SyncDetailsTarget
import dev.stapler.stelekit.git.detailsTarget
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.jobs.JobTracker
import dev.stapler.stelekit.jobs.track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * User actions on sync problems that need a decision: where a badge tap leads, aborting an interrupted
 * merge, and the foreground confirm that lifts the mass-deletion guard for one run. Split out of
 * [GitSyncCoordinator] so each decision is unit-testable without Compose.
 */
class SyncSafetyActions(
    private val activeGitSyncService: StateFlow<GitSyncService?>,
    private val activeGraphIdProvider: () -> String?,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<AppState>,
    private val jobTracker: JobTracker,
    private val currentSyncState: () -> SyncState,
) {
    private fun graphId(): String? = activeGraphIdProvider() ?: uiState.value.currentGraphId

    /**
     * Opens the detail surface for the current sync problem: the conflict screen, the interrupted-merge
     * dialog (abort path) or the mass-deletion confirm. Does nothing when the state has none.
     */
    fun openSyncDetails() {
        when (currentSyncState().detailsTarget()) {
            SyncDetailsTarget.Conflict -> uiState.update { it.copy(conflictResolutionVisible = true) }
            SyncDetailsTarget.RepairNeeded -> uiState.update { it.copy(repairNeededVisible = true) }
            SyncDetailsTarget.MassChange -> uiState.update { it.copy(massChangeConfirmVisible = true) }
            SyncDetailsTarget.None -> Unit
        }
    }

    fun dismissRepairNeeded() = uiState.update { it.copy(repairNeededVisible = false) }

    fun dismissMassChangeConfirm() = uiState.update { it.copy(massChangeConfirmVisible = false) }

    /** Aborts the interrupted merge (the same reset the conflict screen's Cancel uses) and closes the dialog on success. */
    fun abortInterruptedMerge() {
        val graphId = graphId() ?: return
        scope.launch {
            val service = activeGitSyncService.value ?: return@launch
            if (service.abortActiveMerge(graphId).isRight()) dismissRepairNeeded()
        }
    }

    /**
     * Runs one manual sync with the mass-deletion guard lifted for this run only, after the typed-count
     * confirm. Foreground only: scheduled runs never lift the guard (see [GitSyncService.sync]).
     */
    fun confirmMassChangeSync() {
        val graphId = graphId() ?: return
        dismissMassChangeConfirm()
        scope.launch {
            val service = activeGitSyncService.value ?: return@launch
            jobTracker.track(JobKind.GitSync, "Syncing with remote") { handle ->
                service.sync(graphId, liftMassChangeGuard = true).onLeft { handle.fail(it.message) }
            }
        }
    }
}
