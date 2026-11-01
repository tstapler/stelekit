// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import dev.stapler.stelekit.git.SCHEDULED_SYNC_INTERVAL_OPTIONS
import dev.stapler.stelekit.git.ScheduledSyncLine
import dev.stapler.stelekit.git.ScheduledSyncStatus
import dev.stapler.stelekit.git.effectiveScheduledIntervalMinutes
import dev.stapler.stelekit.git.isScheduledIntervalClamped
import dev.stapler.stelekit.git.isScheduledIntervalSelectable
import dev.stapler.stelekit.git.scheduledSyncLine
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** Everything [ScheduledSyncSection] shows and can change; the stack builds it. */
data class ScheduledSyncSettingsState(
    val hasRemote: Boolean,
    val enabled: Boolean,
    val configuredIntervalMinutes: Int,
    val minimumIntervalMinutes: Int,
    /** Checks continue while the app is closed (Android WorkManager). */
    val closedAppChecks: Boolean,
    val backgroundRestricted: Boolean,
    val status: ScheduledSyncStatus,
    val onEnabledChange: (Boolean) -> Unit,
    val onIntervalChange: (Int) -> Unit,
    val onResume: () -> Unit,
    val onSetUpGit: () -> Unit,
    /** Opens the screen that fixes the last failure (the Git setup wizard). */
    val onFix: () -> Unit,
)

internal const val DESKTOP_HELPER = "Checks the remote while SteleKit is open."
internal const val ANDROID_HELPER = "Also checks about every 15 minutes while SteleKit is closed. Open the app to merge."
internal const val RESTRICTED_HELPER = "Background checks are restricted by Android. Checks run while SteleKit is open."
internal const val NO_REMOTE_REASON = "Set up Git sync first"
internal const val ANDROID_FIVE_MINUTE_REASON = "Android runs at most every 15 minutes"
internal const val SWITCH_LABEL = "Check for updates automatically"
private const val MIN_TARGET_DP = 48
private const val STOPPING_DELAY_MILLIS = 2_000L
private const val NOW_REFRESH_MILLIS = 30_000L

/**
 * Settings > Scheduled sync. Until the pull step ships the tick only fetches, so all copy says
 * "check"/"checked", never "sync"/"synced".
 *
 * @param nowMs fixed clock for tests; null follows the wall clock, refreshed every 30 s.
 */
@Composable
fun ScheduledSyncSection(
    state: ScheduledSyncSettingsState,
    modifier: Modifier = Modifier,
    nowMs: Long? = null,
) {
    val now = nowMs ?: rememberWallClockMs()
    val stoppingVisible = rememberStoppingVisible(state)
    val effective = effectiveScheduledIntervalMinutes(state.configuredIntervalMinutes, state.minimumIntervalMinutes)
    val line = scheduledSyncLine(state.enabled, state.status, now, stoppingVisible)

    SettingsSection("Scheduled sync") {
        Column(modifier = modifier.fillMaxWidth()) {
            ToggleRow(state)
            Text(
                text = helperText(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (!state.hasRemote) {
                Text(NO_REMOTE_REASON, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = state.onSetUpGit, modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp)) { Text("Set up Git sync") }
                return@Column
            }
            IntervalPicker(state, effective)
            StatusLine(line, state)
        }
    }
}

@Composable
private fun ToggleRow(state: ScheduledSyncSettingsState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TARGET_DP.dp)
            .toggleable(
                value = state.enabled,
                enabled = state.hasRemote,
                role = Role.Switch,
                onValueChange = state.onEnabledChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(SWITCH_LABEL, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = state.enabled, onCheckedChange = null, enabled = state.hasRemote)
    }
}

@Composable
private fun IntervalPicker(state: ScheduledSyncSettingsState, effective: Int) {
    Column(modifier = Modifier.selectableGroup()) {
        SCHEDULED_SYNC_INTERVAL_OPTIONS.forEach { minutes ->
            val selectable = isScheduledIntervalSelectable(minutes, state.minimumIntervalMinutes)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MIN_TARGET_DP.dp)
                    .selectable(
                        selected = effective == minutes,
                        enabled = selectable,
                        role = Role.RadioButton,
                        onClick = { state.onIntervalChange(minutes) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = effective == minutes, onClick = null, enabled = selectable)
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("$minutes minutes", style = MaterialTheme.typography.bodyLarge)
                    if (!selectable) {
                        Text(
                            ANDROID_FIVE_MINUTE_REASON,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (isScheduledIntervalClamped(state.configuredIntervalMinutes, state.minimumIntervalMinutes)) {
            Text("Runs every $effective min on this device", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusLine(line: ScheduledSyncLine, state: ScheduledSyncSettingsState) {
    if (line is ScheduledSyncLine.Paused) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            Text(
                text = "Paused after ${line.failures} failures - tap to fix",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MIN_TARGET_DP.dp)
                    .clickable(onClick = state.onFix)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            line.lastFailure?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = state.onResume, modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp)) { Text("Resume") }
        }
        return
    }
    Text(
        text = lineText(line),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

internal fun lineText(line: ScheduledSyncLine): String = when (line) {
    ScheduledSyncLine.NotScheduled -> "Not scheduled"
    ScheduledSyncLine.Stopping -> "Stopping..."
    ScheduledSyncLine.NeverChecked -> "Not checked yet"
    ScheduledSyncLine.CheckInProgress -> "Check in progress"
    ScheduledSyncLine.Offline -> "Offline - will check when you are back online"
    is ScheduledSyncLine.Checked -> {
        val ago = if (line.lastCheckedMinutesAgo < 1) "just now" else "${line.lastCheckedMinutesAgo} min ago"
        val next = line.nextInMinutes?.let { " · next in $it min" }.orEmpty()
        "Last checked $ago$next"
    }
    is ScheduledSyncLine.Paused -> "Paused after ${line.failures} failures - tap to fix"
}

private fun helperText(state: ScheduledSyncSettingsState): String = when {
    !state.closedAppChecks -> DESKTOP_HELPER
    state.backgroundRestricted -> RESTRICTED_HELPER
    else -> ANDROID_HELPER
}

@Composable
private fun rememberWallClockMs(): Long {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(NOW_REFRESH_MILLIS)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }
    return now
}

/** "Stopping..." appears only when a tick is still running more than 2 s after the switch went off. */
@Composable
private fun rememberStoppingVisible(state: ScheduledSyncSettingsState): Boolean {
    var visible by remember { mutableStateOf(false) }
    val stopping = !state.enabled && state.status.tickInFlight
    LaunchedEffect(stopping) {
        if (stopping) {
            delay(STOPPING_DELAY_MILLIS)
            visible = true
        } else {
            visible = false
        }
    }
    return visible
}
