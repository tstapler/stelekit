// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.toSyncErrorMessage
import dev.stapler.stelekit.git.BranchRepairProposal
import dev.stapler.stelekit.git.BranchRepairResult
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.redactSecrets
import dev.stapler.stelekit.ui.screens.git.BranchRepairSheet
import dev.stapler.stelekit.ui.screens.git.FirstSyncReviewDialog
import kotlinx.coroutines.launch

private fun SyncState.isRunning(): Boolean =
    this is SyncState.Fetching || this is SyncState.Merging || this is SyncState.Pushing || this is SyncState.Committing

/**
 * Hosts the branch repair sheet (opened from the red "Branch 'x' not found on remote" badge) and
 * the first-sync review that follows a repair. Repairing never syncs; the review's "Sync now" does.
 */
@Composable
internal fun BranchRepairHost(appState: AppState, viewModel: StelekitViewModel, gitSync: GitSyncDeps) {
    val service = gitSync.gitSyncService ?: return
    val graphId = gitSync.activeGraphId ?: return
    val syncState by viewModel.syncState.collectAsState()
    val scope = rememberCoroutineScope()

    if (appState.branchRepairVisible) {
        val error = (syncState as? SyncState.Error)?.error as? DomainError.GitError.RemoteBranchNotFound
        if (error == null) {
            // The error cleared (branch fixed elsewhere, or another sync finished): nothing left to repair.
            LaunchedEffect(Unit) { viewModel.dismissBranchRepair() }
        } else {
            var saving by remember(error) { mutableStateOf(false) }
            var failure by remember(error) { mutableStateOf<BranchRepairResult?>(null) }
            BranchRepairSheet(
                error = error,
                busy = syncState.isRunning(),
                saving = saving,
                failure = failure,
                onUse = { branch ->
                    scope.launch {
                        saving = true
                        val result = service.repairBranch(
                            BranchRepairProposal(graphId, from = error.branch, to = branch, available = error.available),
                        )
                        saving = false
                        if (result is BranchRepairResult.Repaired) {
                            viewModel.setGitConfig(gitSync.gitConfigRepository?.getConfig(graphId)?.getOrNull())
                            viewModel.openFirstSyncReview()
                        } else {
                            failure = result
                        }
                    }
                },
                onDismiss = { viewModel.dismissBranchRepair() },
            )
        }
    }

    if (appState.firstSyncReviewVisible) {
        val config = appState.gitConfig
        var started by remember { mutableStateOf(false) }
        // Ignore the pre-click state (still the old error) until this sync has actually started.
        var sawRunning by remember { mutableStateOf(false) }
        var changeBackNote by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(syncState, started) { if (started && syncState.isRunning()) sawRunning = true }
        val label = config?.let { "${it.remoteName}/${it.remoteBranch}" } ?: "the new branch"
        val resultLine = if (!sawRunning) null else when (val s = syncState) {
            is SyncState.Success -> if (s.remoteCommitsMerged > 0) "Pulled ${s.remoteCommitsMerged} commits" else "Already up to date"
            is SyncState.Error -> s.error.toSyncErrorMessage()
            else -> null
        }
        FirstSyncReviewDialog(
            remoteBranch = label,
            previousBranch = service.previousBranchBeforeRepair(),
            syncing = syncState.isRunning(),
            resultLine = changeBackNote ?: resultLine,
            onSyncNow = {
                started = true
                viewModel.triggerSync()
            },
            onChangeBack = {
                scope.launch {
                    when (val result = service.changeBackBranch()) {
                        is BranchRepairResult.Repaired -> {
                            viewModel.setGitConfig(gitSync.gitConfigRepository?.getConfig(graphId)?.getOrNull())
                            viewModel.dismissFirstSyncReview()
                        }
                        is BranchRepairResult.TargetNotOnRemote ->
                            changeBackNote = "'${result.to}' is still not on the remote, so it can't be switched back to."
                        is BranchRepairResult.SaveFailed -> changeBackNote = "Couldn't switch back: ${redactSecrets(result.message)}"
                        else -> changeBackNote = "Couldn't switch back."
                    }
                }
            },
            onDismiss = { viewModel.dismissFirstSyncReview() },
        )
    }
}

