// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.git.Banner
import dev.stapler.stelekit.git.StalenessSeverity
import dev.stapler.stelekit.git.SyncStatusSheetModel

private const val MIN_TARGET_DP = 48

/** What the sheet's buttons do; none of them starts a sync except [onSyncNow]. */
class SyncStatusSheetActions(
    val onSyncNow: () -> Unit,
    val onReviewFirstSync: () -> Unit,
    val onOpenSettings: (() -> Unit)?,
    val onDismiss: () -> Unit,
)

/** Standalone sync status (UX-101): the chip's facts in full, reachable from the chip, Settings and the notification tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusSheet(model: SyncStatusSheetModel, actions: SyncStatusSheetActions, notice: String? = null) {
    ModalBottomSheet(onDismissRequest = actions.onDismiss) {
        SyncStatusSheetContent(model, actions, notice = notice)
    }
}

@Composable
fun SyncStatusSheetContent(
    model: SyncStatusSheetModel,
    actions: SyncStatusSheetActions,
    modifier: Modifier = Modifier,
    notice: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Sync status", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        Text(model.headline, style = MaterialTheme.typography.titleMedium, color = severityColor(model.severity))
        model.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(row.value, style = MaterialTheme.typography.bodyMedium)
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
        Actions(model, actions)
    }
}

@Composable
private fun Actions(model: SyncStatusSheetModel, actions: SyncStatusSheetActions) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = actions.onSyncNow,
                enabled = model.canSyncNow,
                modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp),
            ) { Text("Sync now") }
            if (model.canReviewFirstSync) {
                OutlinedButton(onClick = actions.onReviewFirstSync, modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp)) {
                    Text("Review first sync")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    StalenessSeverity.Amber -> Color(0xFFF59E0B)
    StalenessSeverity.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    StalenessSeverity.Healthy -> MaterialTheme.colorScheme.onSurface
}
