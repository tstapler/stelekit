// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.GridSelection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

/** The status line recomputes at most once per this many milliseconds while the selection moves (ux.md S10). */
internal const val STATS_PERIOD_MS = 500L

private data class StatsKey(val selection: GridSelection, val revision: Int, val editing: Boolean)

/**
 * The status row (ux.md 3.8, S10): the busy text on the left ("Saving...", "Applying...") and the selection stats on
 * the right, "Count 2  Sum 8  Average 4" plus "1 error", as a polite live region. The first value shows at once; a drag
 * then updates it at most every [STATS_PERIOD_MS], always ending on the latest selection (`delay`, so a test clock drives it).
 */
@Composable
internal fun SheetStatusRow(state: SheetState, busyText: String?) {
    var stats by remember(state) { mutableStateOf<String?>(null) }
    LaunchedEffect(state) {
        snapshotFlow { StatsKey(state.selection, state.statsRevision, state.mode == GridMode.Edit) }
            .conflate()
            .collect { key ->
                stats = if (key.editing) null else state.statsOf(key.selection).text
                delay(STATS_PERIOD_MS)
            }
    }
    if (busyText == null && stats == null) return
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        if (busyText != null) Text(busyText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("busyLine"))
        stats?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("statusLine"),
            )
        }
    }
}
