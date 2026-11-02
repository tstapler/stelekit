package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.asset.pipeline.CLOUD_EXIF_REMOVED_LINE
import dev.stapler.stelekit.asset.pipeline.CLOUD_EXIF_SENT_LINE
import dev.stapler.stelekit.asset.pipeline.CLOUD_NETWORK_ONLINE_LINE
import dev.stapler.stelekit.asset.pipeline.CLOUD_NETWORK_UNMETERED_LINE
import dev.stapler.stelekit.asset.pipeline.CLOUD_POLICY_URL
import dev.stapler.stelekit.asset.pipeline.CLOUD_STORAGE_LINE
import dev.stapler.stelekit.asset.pipeline.CLOUD_WARNING_LINE
import dev.stapler.stelekit.asset.pipeline.CloudBackfillScope
import dev.stapler.stelekit.asset.pipeline.CloudConsentDialogModel

internal const val CLOUD_DIALOG_TITLE = "Send images to Anthropic?"
internal const val CLOUD_ENABLE_LABEL = "Enable uploads"
internal const val CLOUD_CANCEL_LABEL = "Cancel"
internal const val CLOUD_NEW_ONLY_LABEL = "Only images you attach from now on"

internal fun cloudIncludeExistingLabel(model: CloudConsentDialogModel): String =
    "Also the ${model.existingImages} images already in this graph, at most ${model.dailyCap} a day, about ${model.estimatedDays} days"

internal fun cloudCapsLine(model: CloudConsentDialogModel): String =
    "Up to ${model.sessionCap} images per app launch, ${model.dailyCap} per day"

/**
 * The consent dialog. Cancel has initial focus; Cancel, Escape, Back and an outside tap all call
 * [onCancel] and leave consent untouched. Title and buttons stay pinned while the body scrolls.
 * [scopeSaved] state survives rotation and font-scale changes.
 */
@Composable
fun CloudConsentDialog(
    model: CloudConsentDialogModel,
    onEnable: (CloudBackfillScope) -> Unit,
    onCancel: () -> Unit,
) {
    var includeExisting by rememberSaveable { mutableStateOf(false) }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(CLOUD_DIALOG_TITLE) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(CLOUD_WARNING_LINE, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Text("Applies to graph: ${model.graphName}")
                Bullet("Image bytes are sent to Anthropic.")
                Bullet(if (model.exifStripShipped) CLOUD_EXIF_REMOVED_LINE else CLOUD_EXIF_SENT_LINE)
                Bullet(CLOUD_STORAGE_LINE)
                Bullet(if (model.meteredAware) CLOUD_NETWORK_UNMETERED_LINE else CLOUD_NETWORK_ONLINE_LINE)
                Bullet(cloudCapsLine(model))
                Text(
                    "This launch: ${model.launchCount} of ${model.sessionCap}, all graphs; Today: ${model.dailyCount} of ${model.dailyCap}, all graphs",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (model.existingImages > 0) {
                    Column(Modifier.selectableGroup()) {
                        ScopeOption(CLOUD_NEW_ONLY_LABEL, selected = !includeExisting) { includeExisting = false }
                        ScopeOption(cloudIncludeExistingLabel(model), selected = includeExisting) { includeExisting = true }
                    }
                } else {
                    Text(CLOUD_NEW_ONLY_LABEL)
                }
                TextButton(onClick = { runCatching { uriHandler.openUri(CLOUD_POLICY_URL) } }) {
                    Text("Anthropic data handling policy")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onEnable(if (includeExisting && model.existingImages > 0) CloudBackfillScope.IncludeExisting else CloudBackfillScope.NewOnly)
            }) { Text(CLOUD_ENABLE_LABEL) }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) { Text(CLOUD_CANCEL_LABEL) }
        },
    )
}

@Composable
private fun Bullet(text: String) {
    Text("• $text", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ScopeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
