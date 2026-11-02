package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.asset.pipeline.AssetPluginsController
import dev.stapler.stelekit.asset.pipeline.PluginEnableKeys

internal const val SAVE_FAILED_TEXT = "Couldn't save this setting"
internal const val SUGGESTION_AGAIN_TEXT = "Show the searchable-images suggestion again"

private class PluginRowSpec(val id: String, val title: String, val helper: String)

private val PLUGIN_ROWS = listOf(
    PluginRowSpec(PluginEnableKeys.TEXT_RECOGNITION, "Text recognition", "Finds text in images so you can search for it. Off by default."),
    PluginRowSpec(PluginEnableKeys.IMAGE_LABELS, "Image labels", "Adds labels such as \"cat\" to images. Off by default."),
    PluginRowSpec(PluginEnableKeys.PDF_TEXT, "PDF text", "Makes the text inside PDFs searchable. Off by default."),
)

/** One switch per on-device plugin, bound to its `asset_plugin_<id>_enabled` key through [AssetPluginsController]. */
@Composable
internal fun PluginSwitchesSection(controller: AssetPluginsController, onViewLog: () -> Unit) {
    val states by controller.registry.enabledStates.collectAsState()
    var saveFailed by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PLUGIN_ROWS.forEach { row ->
            val reason = controller.registry.unavailableReason(row.id)
            val checked = reason == null && states[row.id] == true
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .toggleable(
                        value = checked,
                        enabled = reason == null,
                        role = Role.Switch,
                        onValueChange = { on -> saveFailed = controller.setPluginEnabled(row.id, on).isLeft() },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        reason ?: row.helper,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = checked, onCheckedChange = null, enabled = reason == null)
            }
        }
        if (saveFailed) {
            Text(
                SAVE_FAILED_TEXT,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onViewLog) { Text("View log") }
        }
    }
}

/** Clears the Assets "Not now" dismissal; hidden while the card has not been dismissed. */
@Composable
internal fun SearchableSuggestionRow(controller: AssetPluginsController) {
    val dismissed by controller.promptDismissed.collectAsState()
    if (!dismissed) return
    TextButton(
        onClick = controller::clearPromptDismissal,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) { Text(SUGGESTION_AGAIN_TEXT) }
}
