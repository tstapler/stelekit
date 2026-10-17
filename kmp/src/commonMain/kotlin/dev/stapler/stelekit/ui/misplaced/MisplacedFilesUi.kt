// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.misplaced

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.db.ProposedResolution
import dev.stapler.stelekit.ui.components.typedItems

/** The banner, the review dialog and the result message for [controller]; shows nothing when there is nothing to do. */
@Composable
fun MisplacedFilesHost(controller: MisplacedFilesController) {
    val state by controller.state.collectAsState()
    DisposableEffect(controller) { onDispose { controller.close() } }

    state.summary?.let { MisplacedFilesSummary(it, onDismiss = controller::dismissSummary) }
    if (state.items.isNotEmpty()) {
        MisplacedFilesBanner(count = state.items.size, onReview = controller::openDialog, onIgnore = controller::ignoreAll)
    }
    if (state.dialogVisible && state.items.isNotEmpty()) {
        MisplacedFilesDialog(
            state = state,
            onMove = { controller.move(listOf(it.file)) },
            onIgnore = { controller.ignore(listOf(it.file)) },
            onMoveAll = controller::moveAll,
            onIgnoreAll = controller::ignoreAll,
            onClose = controller::closeDialog,
        )
    }
}

@Composable
internal fun MisplacedFilesBanner(count: Int, onReview: () -> Unit, onIgnore: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Merge, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (count == 1) "1 note is outside your notes folder" else "$count notes are outside your notes folder",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "They are not synced. Review to move or merge them, or ignore them.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onReview) { Text("Review") }
            TextButton(onClick = onIgnore) { Text("Ignore") }
        }
    }
}

@Composable
internal fun MisplacedFilesSummary(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
internal fun MisplacedFilesDialog(
    state: MisplacedFilesUiState,
    onMove: (MisplacedItem) -> Unit,
    onIgnore: (MisplacedItem) -> Unit,
    onMoveAll: () -> Unit,
    onIgnoreAll: () -> Unit,
    onClose: () -> Unit,
) {
    val movable = state.items.count { it.movable }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Notes outside your notes folder") },
        text = {
            Column {
                Text(
                    "Nothing moves until you choose. Moving a page that also exists in your notes merges the two, " +
                        "keeping every block, and backs up both originals first.",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    typedItems(state.items, key = { it.file.relativePath }) { item ->
                        MisplacedItemRow(item, busy = state.busy, onMove = { onMove(item) }, onIgnore = { onIgnore(item) })
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onMoveAll, enabled = movable > 0 && !state.busy) {
                Text(if (movable > 0) "Move all ($movable)" else "Move all")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onIgnoreAll, enabled = !state.busy) { Text("Ignore all") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}

@Composable
private fun MisplacedItemRow(item: MisplacedItem, busy: Boolean, onMove: () -> Unit, onIgnore: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(item.file.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(item.file.relativePath, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(misplacedStatusText(item), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        Row {
            TextButton(onClick = onMove, enabled = item.movable && !busy) { Text(if (item.file.hasCopyInNotes) "Merge" else "Move") }
            TextButton(onClick = onIgnore, enabled = !busy) { Text("Ignore") }
        }
    }
}

/** One plain sentence on what moving [item] would do. */
internal fun misplacedStatusText(item: MisplacedItem): String = when (val proposal = item.proposal) {
    null -> "Checking…"
    ProposedResolution.Missing -> "No longer there."
    ProposedResolution.MoveAsNew -> "Only here. Moving it adds it to your notes."
    ProposedResolution.IdenticalCopy -> "Identical to the copy in your notes. Moving it just removes this duplicate."
    is ProposedResolution.MergeIntoExisting ->
        "Also in your notes. Merging adds ${proposal.blocksToAdd} ${if (proposal.blocksToAdd == 1) "block" else "blocks"} " +
            "to that page and removes nothing."
    is ProposedResolution.CannotMerge -> "Can't merge automatically: ${proposal.reason}. Both copies are kept."
}
