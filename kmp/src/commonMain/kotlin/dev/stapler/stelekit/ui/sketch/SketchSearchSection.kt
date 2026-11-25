// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.repository.SketchSearchHit
import dev.stapler.stelekit.sketch.v2.runtime.persist.SketchTextSearch
import dev.stapler.stelekit.ui.i18n.I18n
import dev.stapler.stelekit.ui.i18n.t
import kotlinx.coroutines.delay

internal const val SKETCH_SEARCH_DEBOUNCE_MS = 250L

/**
 * The "Sketches" section under the global-search results (E8): sketches whose text, names or dimensions contain the query.
 * It runs the one-time [SketchTextSearch.backfill] when first shown, queries after a debounce the next keystroke cancels,
 * and shows nothing when nothing matches, so a graph with no sketches never sees it.
 */
@Composable
fun SketchSearchSection(
    query: String,
    search: SketchTextSearch,
    onOpenSketch: (projectUuid: String) -> Unit,
    modifier: Modifier = Modifier,
    debounceMs: Long = SKETCH_SEARCH_DEBOUNCE_MS,
) {
    var hits by remember { mutableStateOf<List<SketchSearchHit>>(emptyList()) }
    LaunchedEffect(search) { search.backfill() }
    LaunchedEffect(query, search) {
        if (query.isBlank()) {
            hits = emptyList()
            return@LaunchedEffect
        }
        delay(debounceMs)
        hits = search.search(query.trim())
    }
    if (hits.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(t(I18n.SKETCH_SEARCH_SECTION), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        hits.forEach { hit ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onOpenSketch(hit.projectUuid) }
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(hit.projectName, style = MaterialTheme.typography.bodyLarge)
                if (hit.snippet.isNotEmpty()) {
                    Text(hit.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
