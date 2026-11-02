package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.asset.pipeline.CLOUD_DELETE_LABEL
import dev.stapler.stelekit.asset.pipeline.CLOUD_REVOKE_COPY
import dev.stapler.stelekit.asset.pipeline.CloudConsent
import dev.stapler.stelekit.asset.pipeline.CloudConsentDialogModel
import dev.stapler.stelekit.asset.pipeline.CloudSettingsController
import dev.stapler.stelekit.asset.pipeline.cloudDeleteConfirmText
import kotlinx.coroutines.launch

internal const val CLOUD_SWITCH_LABEL = "Cloud description (Anthropic)"
internal const val CLOUD_KEY_PRESENT_LINE = "Using your Anthropic key from LLM settings"
internal const val CLOUD_KEY_MISSING_LINE = "Add an Anthropic key in LLM settings to use this."
internal const val CLOUD_FORGET_LABEL = "Forget API key"
internal const val CLOUD_FORGET_CONFIRM = "This also stops Claude voice formatting, which uses the same key."

/**
 * The cloud row on Settings > Plugins. Flipping the switch on only opens the consent dialog; the
 * switch moves when "Enable uploads" is tapped. Flipping it off revokes immediately.
 */
@Composable
fun CloudSettingsSection(controller: CloudSettingsController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<CloudConsentDialogModel?>(null) }
    var deleteCount by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(controller) { controller.refresh() }

    Column(modifier = modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(CLOUD_SWITCH_LABEL, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.keyPresent) CLOUD_KEY_PRESENT_LINE else CLOUD_KEY_MISSING_LINE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.switchOn,
                enabled = state.keyPresent || state.switchOn,
                onCheckedChange = { wantOn ->
                    if (wantOn) scope.launch { dialog = controller.dialogModel() } else controller.revoke()
                },
            )
        }
        Text(
            "This launch: ${state.launchCount} of ${state.sessionCap}, all graphs; Today: ${state.dailyCount} of ${state.dailyCap}, all graphs",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.switchOn && state.consent is CloudConsent.Revoked) {
            Text(CLOUD_REVOKE_COPY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { scope.launch { deleteCount = controller.aiDescriptionCount() } }) { Text(CLOUD_DELETE_LABEL) }
        if (state.keyPresent) {
            TextButton(onClick = { confirmForget = true }) { Text(CLOUD_FORGET_LABEL) }
        }
    }
    dialog?.let { model ->
        CloudConsentDialog(
            model = model,
            onEnable = { chosen -> dialog = null; controller.enable(chosen) },
            onCancel = { dialog = null },
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(CLOUD_FORGET_LABEL) },
            text = { Text(CLOUD_FORGET_CONFIRM) },
            confirmButton = { TextButton(onClick = { confirmForget = false; controller.forgetApiKey() }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
    deleteCount?.let { count ->
        AlertDialog(
            onDismissRequest = { deleteCount = null },
            title = { Text(CLOUD_DELETE_LABEL) },
            text = { Text(cloudDeleteConfirmText(controller.graphDisplayName(), count)) },
            confirmButton = { TextButton(onClick = { deleteCount = null; scope.launch { controller.deleteAiDescriptions() } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteCount = null }) { Text("Cancel") } },
        )
    }
}
