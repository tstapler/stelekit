// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.git.NotificationPermissionAccess
import dev.stapler.stelekit.git.NotifyPermissionFlow
import dev.stapler.stelekit.git.NotifyPermissionState
import dev.stapler.stelekit.git.ToggleOutcome
import dev.stapler.stelekit.git.UpdatesNotificationPreference
import dev.stapler.stelekit.git.UpdatesNotificationToggle
import kotlinx.coroutines.launch

/** Settings' "Notify me when updates are waiting" toggle; null where the platform has no notification permission seam. */
class UpdatesNotificationSettingsState(
    val access: NotificationPermissionAccess,
    val preference: UpdatesNotificationPreference,
) {
    val toggle = UpdatesNotificationToggle(access, preference)
}

internal const val NOTIFY_SWITCH_LABEL = "Notify me when updates are waiting"
internal const val NOTIFY_RATIONALE_TITLE = "Allow notifications?"
internal const val NOTIFY_RATIONALE_BODY =
    "SteleKit posts one quiet notification when remote changes have been waiting a while. It never syncs by itself from the notification."
internal const val NOTIFY_CONTINUE = "Continue"
internal const val NOTIFY_CANCEL = "Not now"
internal const val NOTIFY_BLOCKED = "Notifications are blocked for SteleKit in system settings."
internal const val NOTIFY_OPEN_SETTINGS = "Open system settings"
internal const val NOTIFY_DENIED = "Notifications stay off because the permission was not granted."
private const val MIN_TARGET_DP = 48

@Composable
fun UpdatesNotificationSection(state: UpdatesNotificationSettingsState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val enabled by state.preference.enabled.collectAsState()
    // Re-read on every entry to composition: the user may have changed it in system settings meanwhile.
    var permission by remember { mutableStateOf(state.access.state()) }
    var showRationale by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val blocked = permission == NotifyPermissionState.PermanentlyDenied
    val on = enabled && NotifyPermissionFlow.canPost(permission)

    fun handle(outcome: ToggleOutcome) {
        permission = state.access.state()
        showRationale = outcome == ToggleOutcome.NeedsRationale
        denied = outcome is ToggleOutcome.Denied
    }

    SettingsSection("Notifications") {
        Column(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MIN_TARGET_DP.dp)
                    .toggleable(value = on, enabled = !blocked, role = Role.Switch) { handle(state.toggle.onToggle(it)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(NOTIFY_SWITCH_LABEL, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = null, enabled = !blocked)
            }
            if (blocked) {
                Text(NOTIFY_BLOCKED, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
                TextButton(
                    onClick = state.access::openSystemSettings,
                    modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp),
                ) { Text(NOTIFY_OPEN_SETTINGS) }
            } else if (denied) {
                Text(NOTIFY_DENIED, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text(NOTIFY_RATIONALE_TITLE) },
            text = { Text(NOTIFY_RATIONALE_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    scope.launch { handle(state.toggle.continueAfterRationale()) }
                }) { Text(NOTIFY_CONTINUE) }
            },
            dismissButton = { TextButton(onClick = { showRationale = false }) { Text(NOTIFY_CANCEL) } },
        )
    }
}
