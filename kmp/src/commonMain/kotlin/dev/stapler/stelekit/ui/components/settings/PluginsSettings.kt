package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.stapler.stelekit.asset.pipeline.AssetPluginsController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.asset.AssetIndexProgress
import dev.stapler.stelekit.asset.AssetIndexingController

/**
 * Settings > Plugins: the "Asset indexing" master switch, then (when [plugins] is wired) one
 * switch per on-device plugin and the pipeline progress row. Shown only when [assetIndexing]
 * exists and this build exposes the switch ([AssetIndexingController.toggleVisible]; hidden in
 * release variants before the heap gate), so release builds keep the placeholder.
 */
@Composable
fun PluginsSettings(
    assetIndexing: AssetIndexingController? = null,
    plugins: AssetPluginsController? = null,
    onViewLog: () -> Unit = {},
    scrollToPluginList: Boolean = false,
) {
    if (assetIndexing != null && assetIndexing.toggleVisible) {
        val listRequester = remember { BringIntoViewRequester() }
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            AssetIndexingRow(assetIndexing)
            if (plugins != null) {
                LaunchedEffect(plugins) { plugins.registry.load() }
                LaunchedEffect(scrollToPluginList) { if (scrollToPluginList) listRequester.bringIntoView() }
                PipelineProgressSection(plugins, onViewLog)
                Column(modifier = Modifier.bringIntoViewRequester(listRequester)) {
                    PluginSwitchesSection(plugins, onViewLog)
                }
                SearchableSuggestionRow(plugins)
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No plugins installed", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal const val ASSET_INDEXING_BREAKER_TEXT = "Turned off after repeated crashes. Switch on to try again."
internal const val ASSET_INDEXING_HELPER = "Scans attached files so they appear in Assets. Off by default."

@Composable
private fun AssetIndexingRow(controller: AssetIndexingController) {
    val enabled by controller.enabled.collectAsState()
    val progress by controller.progress.collectAsState()
    val breakerTripped by controller.breakerTripped.collectAsState()
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Asset indexing", style = MaterialTheme.typography.titleMedium)
                Text(
                    ASSET_INDEXING_HELPER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = controller::setEnabled)
        }
        if (breakerTripped && !enabled) {
            Text(
                ASSET_INDEXING_BREAKER_TEXT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        assetIndexStatusLine(enabled, progress)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Status text from the index walk's own progress (files indexed of files found), or null when idle/off. */
internal fun assetIndexStatusLine(enabled: Boolean, progress: AssetIndexProgress): String? = when {
    !enabled -> null
    progress is AssetIndexProgress.Counting -> "Indexing files..."
    progress is AssetIndexProgress.Indexing && progress.done == 0 -> "Indexing files..."
    progress is AssetIndexProgress.Indexing -> "${progress.done} of ${progress.total} indexed"
    progress is AssetIndexProgress.Finished -> "${progress.total} of ${progress.total} indexed"
    else -> null
}
