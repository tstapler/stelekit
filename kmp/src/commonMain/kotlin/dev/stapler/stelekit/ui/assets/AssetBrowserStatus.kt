package dev.stapler.stelekit.ui.assets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.pipeline.AssetPluginsController
import dev.stapler.stelekit.asset.pipeline.PauseReason
import dev.stapler.stelekit.asset.pipeline.PipelineProgress
import dev.stapler.stelekit.ui.components.settings.groupedCount

internal const val SEARCHABLE_PROMPT_TEXT = "Make images searchable - turn on text recognition"

/** True when the "Make images searchable" card should show: an image exists, no plugin runs, not dismissed. */
internal fun shouldShowSearchablePrompt(hasImage: Boolean, enabledPlugins: Int, dismissed: Boolean): Boolean =
    hasImage && enabledPlugins == 0 && !dismissed

/** The one-line "Processing 340 of 2,100 - details" status above the grid; null when idle. */
internal fun assetBrowserProcessingLine(progress: PipelineProgress): String? = when (progress) {
    is PipelineProgress.Running -> "Processing ${groupedCount(progress.done)} of ${groupedCount(progress.total)}"
    is PipelineProgress.Paused -> if (progress.reason == PauseReason.Charger) "Waiting for charger" else null
    else -> null
}

/**
 * Dismissible prompt card and inline pipeline status shown above the Assets grid. "Turn on" opens
 * Settings > Plugins (it never flips a switch itself); "Not now" persists the dismissal.
 */
@Composable
internal fun AssetBrowserStatusHeader(
    assets: List<dev.stapler.stelekit.asset.AssetEntry>,
    controller: AssetPluginsController,
    onOpenPlugins: () -> Unit,
) {
    val enabledCount by controller.registry.enabledCount.collectAsState()
    val dismissed by controller.promptDismissed.collectAsState()
    val progress by controller.progress.collectAsState()
    val hasImage = assets.any { it.mediaType == AssetMediaType.IMAGE }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        assetBrowserProcessingLine(progress)?.let { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(line, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onOpenPlugins) { Text("details") }
            }
        }
        if (shouldShowSearchablePrompt(hasImage, enabledCount, dismissed)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(SEARCHABLE_PROMPT_TEXT, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onOpenPlugins) { Text("Turn on") }
                        TextButton(onClick = controller::dismissPrompt) { Text("Not now") }
                    }
                }
            }
        }
    }
}

/** "No matches", plus how many images are still waiting for processing and a link to the progress row. */
@Composable
internal fun AssetSearchEmptyState(pending: Long, onDetails: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(searchEmptyText(pending), style = MaterialTheme.typography.bodyMedium)
        if (pending > 0) TextButton(onClick = onDetails) { Text("details") }
    }
}

internal fun searchEmptyText(pending: Long): String =
    if (pending > 0) "No matches. ${groupedCount(pending.toInt())} images are still being processed" else "No matches"
