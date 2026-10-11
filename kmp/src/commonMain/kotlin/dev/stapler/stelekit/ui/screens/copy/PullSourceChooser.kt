// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.copy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.merge.PullIndexState
import dev.stapler.stelekit.merge.ReadError

/**
 * Source chooser (design/ux.md S3, pull variant): every other registered graph is a row that is
 * selectable only when readable, otherwise disabled with its reason (and "Re-select folder" where
 * the grant can be renewed). The active graph is the destination and shows as "current graph".
 * When no row is usable the screen says so and offers Add a graph / Close.
 */
@Composable
internal fun PullSourceChooser(state: CopyPagesState, actions: CopyPagesActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
        Text("Copy from:", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        if (state.noOtherGraph) {
            Text("You need a second graph to copy pages.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            ExitRow(actions)
            return@Column
        }
        state.destinations.forEach { DestinationRowView(it, state.destinationId == it.graphId, actions) }
        if (state.noSourceAvailable) {
            Text(
                "No graph can be read from here.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            ExitRow(actions)
        }
    }
}

@Composable
private fun ExitRow(actions: CopyPagesActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = actions.onAddGraph, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add a graph") }
        TextButton(onClick = actions.onRequestClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
    }
}

/**
 * Name-index status under the picker header: "Reading <graph>... N files found" with Stop while the
 * listing streams in (the list is already usable), or the per-source read error with
 * Retry / Re-select folder / Close.
 */
@Composable
internal fun PullIndexStatus(state: CopyPagesState, actions: CopyPagesActions, modifier: Modifier = Modifier) {
    val source = state.chosenDestination?.name ?: return
    when (val index = state.indexState) {
        is PullIndexState.Reading -> Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                "Reading $source... ${index.filesFound} files found",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = actions.onStopReading, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop") }
        }
        is PullIndexState.Failed -> Column(modifier.fillMaxWidth()) {
            Text(
                "Couldn't read $source: ${index.error.message}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = actions.onRetryIndex, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
                if (index.error is ReadError.NoGrant) {
                    TextButton(
                        onClick = { state.destinationId?.let { actions.onDestinationAction(it, DestinationActionKind.RegrantAccess) } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(CopyPagesState.RESELECT_FOLDER) }
                }
                TextButton(onClick = actions.onRequestClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
            }
        }
        else -> Unit
    }
}
