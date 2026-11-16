// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.git.Banner
import dev.stapler.stelekit.git.StalenessSeverity
import dev.stapler.stelekit.git.SyncDetailsTarget
import dev.stapler.stelekit.git.SyncSheetState
import dev.stapler.stelekit.git.SyncStatusSheetModel
import dev.stapler.stelekit.ui.theme.StelekitTheme

private const val MIN_TARGET_DP = 48

/** What the sheet's buttons do; none of them starts a sync except [onSyncNow]. */
class SyncStatusSheetActions(
    val onSyncNow: () -> Unit,
    val onReviewFirstSync: () -> Unit,
    val onOpenSettings: (() -> Unit)?,
    val onDismiss: () -> Unit,
    /** Opens the conflict screen or repair dialog behind [SyncStatusSheetModel.problem]. */
    val onOpenProblem: (() -> Unit)? = null,
    /** Removes a leftover lock file after the confirm dialog; null hides the entry point. */
    val onRemoveLockFile: (() -> Unit)? = null,
)

/** Standalone sync status (UX-101): the chip's facts in full, reachable from the chip, Settings and the notification tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusSheet(
    model: SyncStatusSheetModel,
    actions: SyncStatusSheetActions,
    notice: String? = null,
    announcement: String? = null,
) {
    ModalBottomSheet(onDismissRequest = actions.onDismiss) {
        SyncStatusSheetContent(model, actions, notice = notice, announcement = announcement)
    }
}

@Composable
fun SyncStatusSheetContent(
    model: SyncStatusSheetModel,
    actions: SyncStatusSheetActions,
    modifier: Modifier = Modifier,
    notice: String? = null,
    /** One polite live-region message (paused, failed, already syncing); changing it makes TalkBack read it once. */
    announcement: String? = null,
    /** True while Cancel waits for a non-cancellable git call to return. */
    stopping: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Sync status", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        announcement?.let { LiveRegion(it) }
        notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        Text(model.headline, style = MaterialTheme.typography.titleMedium, color = severityColor(model.severity))
        model.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(row.value, style = MaterialTheme.typography.bodyMedium)
            }
        }
        StateBlock(model, actions)
        model.problem?.takeIf { model.state !is SyncSheetState.Failed }?.let { problem ->
            Text(problem.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            val open = actions.onOpenProblem
            if (open != null && problem.target != SyncDetailsTarget.None) {
                Button(onClick = open, modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp)) { Text(problemActionLabel(problem.target)) }
            }
        }
        model.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        model.bannerChoice.banner?.let(::bannerSummary)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (model.recent.isNotEmpty()) {
            Text("Recent checks", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            model.recent.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Actions(model, actions, stopping)
    }
}

@Composable
private fun LiveRegion(message: String) {
    Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = message })
}

@Composable
private fun StateBlock(model: SyncStatusSheetModel, actions: SyncStatusSheetActions) {
    when (val state = model.state) {
        is SyncSheetState.Running -> Column {
            Text(
                "Syncing..." + (state.phase?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.followUpLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        is SyncSheetState.Paused -> PausedBlock(state, model.retryNow, actions)
        is SyncSheetState.Failed -> FailedBlock(state, actions)
        SyncSheetState.Idle, SyncSheetState.Attention -> Unit
    }
}

@Composable
private fun PausedBlock(state: SyncSheetState.Paused, retryNow: (() -> Unit)?, actions: SyncStatusSheetActions) {
    var detailsOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
        "Sync paused",
        style = MaterialTheme.typography.titleMedium,
        color = StelekitTheme.colors.warning,
        modifier = Modifier.semantics { heading() },
    )
    Text(state.body, style = MaterialTheme.typography.bodyMedium)
    Button(
        onClick = { retryNow?.invoke() },
        enabled = retryNow != null,
        modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp),
    ) { Text("Retry now") }
    DetailsToggle(open = detailsOpen, onToggle = { detailsOpen = !detailsOpen })
    if (detailsOpen) {
        Text(state.details, style = MaterialTheme.typography.bodySmall)
        if (state.canRemoveLock) RemoveLockEntry(actions)
    }
    }
}

@Composable
private fun FailedBlock(state: SyncSheetState.Failed, actions: SyncStatusSheetActions) {
    var detailsOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
        state.text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { heading() },
    )
    DetailsToggle(open = detailsOpen, onToggle = { detailsOpen = !detailsOpen })
    if (detailsOpen) {
        Text("We couldn't confirm the lock owner after two automatic tries. Sync now starts a fresh run.", style = MaterialTheme.typography.bodySmall)
        if (state.canRemoveLock) RemoveLockEntry(actions)
    }
    }
}

@Composable
private fun DetailsToggle(open: Boolean, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp)) { Text(if (open) "Hide details" else "Details") }
}

/** The one-tap row never offers this; here a confirm dialog (initial focus on Cancel) guards the removal. */
@Composable
private fun RemoveLockEntry(actions: SyncStatusSheetActions) {
    val remove = actions.onRemoveLockFile ?: return
    var confirming by remember { mutableStateOf(false) }
    Column {
    OutlinedButton(onClick = { confirming = true }, modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp)) { Text("Remove lock file...") }
    if (confirming) {
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Remove the git lock file?") },
            text = { Text("Do this only if no other sync or git app is running. Removing a lock that is in use can damage the repository.") },
            dismissButton = {
                TextButton(onClick = { confirming = false }, modifier = Modifier.focusRequester(cancelFocus).heightIn(min = MIN_TARGET_DP.dp)) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; remove() }, modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp)) { Text("Remove") }
            },
        )
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(model: SyncStatusSheetModel, actions: SyncStatusSheetActions, stopping: Boolean) {
    val running = model.state is SyncSheetState.Running
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (model.state !is SyncSheetState.Paused) {
                Button(
                    onClick = actions.onSyncNow,
                    enabled = model.canSyncNow,
                    modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp).semantics {
                        if (running) stateDescription = "A sync is already running"
                    },
                ) { Text(if (running) "Syncing..." else "Sync now") }
            }
            if (running && stopping) {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp)) { Text("Stopping...") }
            }
            if (model.canReviewFirstSync) {
                OutlinedButton(onClick = actions.onReviewFirstSync, modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp)) {
                    Text("Review first sync")
                }
            }
        }
        model.syncNowDisabledReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.onOpenSettings?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp)) { Text("Open sync settings") } }
            TextButton(onClick = actions.onDismiss, modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp)) { Text("Close") }
        }
    }
}

/** The mismatch banner keeps its own host, so the sheet has no line for it. */
private fun bannerSummary(banner: Banner): String? = when (banner) {
    Banner.FirstSyncReview -> "Review the first sync before SteleKit syncs this graph for you."
    Banner.UpdatesWaiting -> "Updates are waiting on the remote."
    Banner.Mismatch -> null
}

@Composable
private fun severityColor(severity: StalenessSeverity): Color = when (severity) {
    StalenessSeverity.Amber -> StelekitTheme.colors.warning
    StalenessSeverity.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    StalenessSeverity.Healthy -> MaterialTheme.colorScheme.onSurface
}

private fun problemActionLabel(target: SyncDetailsTarget): String = when (target) {
    SyncDetailsTarget.Conflict -> "Resolve conflicts"
    SyncDetailsTarget.RepairNeeded -> "Fix unfinished merge"
    SyncDetailsTarget.MassChange -> "Review deletions"
    SyncDetailsTarget.None -> ""
}
