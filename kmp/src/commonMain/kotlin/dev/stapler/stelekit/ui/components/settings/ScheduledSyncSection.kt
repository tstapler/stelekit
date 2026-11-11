// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.stapler.stelekit.git.ANDROID_MIN_INTERVAL_HELPER
import dev.stapler.stelekit.git.BATTERY_SETTINGS_MANUAL_PATH
import dev.stapler.stelekit.git.BatterySettingsLauncher
import dev.stapler.stelekit.git.BatterySettingsResult
import dev.stapler.stelekit.git.OPEN_BATTERY_SETTINGS_LABEL
import dev.stapler.stelekit.git.RestrictedHint
import dev.stapler.stelekit.git.StatusSkip
import dev.stapler.stelekit.git.restrictedHint
import dev.stapler.stelekit.git.statusLineText
import dev.stapler.stelekit.jobs.schedule.RestrictionReason
import dev.stapler.stelekit.jobs.schedule.ScheduleLateness
import dev.stapler.stelekit.jobs.schedule.SchedulerCapabilities
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
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
    /** No manual, previewed sync has succeeded yet for this remote and branch (UW-16): the switch cannot be turned on. */
    val firstSyncReviewRequired: Boolean = false,
    val onReviewFirstSync: () -> Unit = {},
    /** Opens the standalone sync status sheet. */
    val onOpenStatus: () -> Unit = {},
    /** From `lateness(...)` over the git last-success source; Late shows only where [capabilities] allow it. */
    val lateness: ScheduleLateness = ScheduleLateness.OnTime,
    /** Null (unknown) never shows Late. */
    val capabilities: SchedulerCapabilities? = null,
    val restriction: RestrictionReason = RestrictionReason.None,
    /** A background process could not read task settings since the last success. */
    val settingsUnreadable: Boolean = false,
    /** Re-reads the settings store and clears the unreadable marker when that succeeds. */
    val onRetrySettings: () -> Unit = {},
    /** The existing vault unlock flow ("Locked. Sync resumes after you unlock."). */
    val onUnlock: () -> Unit = {},
    /** The existing conflict review ("Resolve the conflict to resume."). */
    val onReviewConflict: () -> Unit = {},
    /** Null on platforms without a battery settings page: the hint shows the manual path instead of a button. */
    val batterySettingsLauncher: BatterySettingsLauncher? = null,
)

internal const val DESKTOP_HELPER = "Syncs with the remote while SteleKit is open."
internal const val ANDROID_HELPER = "Also checks about every 15 minutes while SteleKit is closed. Open the app to merge."
internal const val RESTRICTED_HELPER = "Background checks are restricted by Android. Syncs run while SteleKit is open."
internal const val NO_REMOTE_REASON = "Set up Git sync first"
internal const val REVIEW_FIRST_SYNC_REASON = "Review first sync to enable"
internal const val ANDROID_FIVE_MINUTE_REASON = "Android runs at most every 15 minutes"
internal const val SWITCH_LABEL = "Sync automatically"
internal const val UNLOCK_LABEL = "Unlock"
internal const val REVIEW_CONFLICT_LABEL = "Review conflict"
internal const val VIEW_STATUS_LABEL = "View sync status"
private const val MIN_TARGET_DP = 48
private const val STOPPING_DELAY_MILLIS = 2_000L
private const val NOW_REFRESH_MILLIS = 30_000L

/**
 * Settings > Scheduled sync. A tick now pulls and pushes (UW-17), so the status line says
 * "sync"/"synced"; only the closed-app helper still says "checks" because the closed app only fetches.
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
    val line = scheduledSyncLine(
        state.enabled, state.status, now, stoppingVisible,
        lateness = state.lateness, capabilities = state.capabilities, settingsUnreadable = state.settingsUnreadable,
    )
    val hint = restrictedHint(line, state.restriction, state.enabled, state.capabilities)

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
            if (state.firstSyncReviewRequired && !state.enabled) {
                Text(REVIEW_FIRST_SYNC_REASON, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = state.onReviewFirstSync, modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp)) { Text("Review first sync") }
            }
            IntervalPicker(state, effective)
            StatusLine(line, state)
            hint?.let { RestrictedHintRow(it, state.batterySettingsLauncher) }
            TextButton(onClick = state.onOpenStatus, modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp)) { Text(VIEW_STATUS_LABEL) }
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
                enabled = switchEnabled(state),
                role = Role.Switch,
                onValueChange = state.onEnabledChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(SWITCH_LABEL, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = state.enabled, onCheckedChange = null, enabled = switchEnabled(state))
    }
}

/** Off needs a remote and a confirmed first sync; an already-on switch stays usable so it can always be turned off. */
private fun switchEnabled(state: ScheduledSyncSettingsState): Boolean =
    state.hasRemote && (!state.firstSyncReviewRequired || state.enabled)

@Composable
private fun IntervalPicker(state: ScheduledSyncSettingsState, effective: Int) {
    Column(modifier = Modifier.selectableGroup()) {
        // No permanently disabled control: options below the platform floor are not listed, a helper line says why.
        SCHEDULED_SYNC_INTERVAL_OPTIONS.filter { isScheduledIntervalSelectable(it, state.minimumIntervalMinutes) }.forEach { minutes ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MIN_TARGET_DP.dp)
                    .selectable(
                        selected = effective == minutes,
                        role = Role.RadioButton,
                        onClick = { state.onIntervalChange(minutes) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = effective == minutes, onClick = null)
                Text("$minutes minutes", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
            }
        }
        if (SCHEDULED_SYNC_INTERVAL_OPTIONS.any { !isScheduledIntervalSelectable(it, state.minimumIntervalMinutes) }) {
            Text(ANDROID_MIN_INTERVAL_HELPER, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    // State is never colour alone: Late, skip and unreadable lines carry an icon beside the text.
    val icon = when (line) {
        is ScheduledSyncLine.Late, ScheduledSyncLine.SettingsUnreadable -> Icons.Default.Warning
        is ScheduledSyncLine.SkippedBecause -> Icons.Default.Info
        else -> null
    }
    Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp)) }
        Text(
            text = lineText(line),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    when {
        line == ScheduledSyncLine.SettingsUnreadable -> ActionButton(RETRY_LABEL, state.onRetrySettings)
        line == ScheduledSyncLine.SkippedBecause(StatusSkip.VaultLocked) -> ActionButton(UNLOCK_LABEL, state.onUnlock)
        line == ScheduledSyncLine.SkippedBecause(StatusSkip.ConflictPending) -> ActionButton(REVIEW_CONFLICT_LABEL, state.onReviewConflict)
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp)) { Text(label) }
}

/**
 * S4: a non-modal info row under the status line. "Open battery settings" launches the app's system
 * settings page; if that cannot open the row becomes the manual path (selectable, not clickable) and a
 * warning is logged. It clears with the hint, since the row is only composed while the hint applies.
 */
@Composable
private fun RestrictedHintRow(hint: RestrictedHint, launcher: BatterySettingsLauncher?) {
    var failed by remember(hint) { mutableStateOf(false) }
    val showButton = hint.hasSettingsButton && launcher != null && !failed
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
        Column {
            Text(hint.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (showButton) {
                TextButton(
                    onClick = { if (!launchBatterySettings(launcher)) failed = true },
                    modifier = Modifier.heightIn(min = MIN_TARGET_DP.dp),
                ) { Text(OPEN_BATTERY_SETTINGS_LABEL) }
            } else if (hint.hasSettingsButton) {
                SelectionContainer { Text(BATTERY_SETTINGS_MANUAL_PATH, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

private val hintLogger = Logger("ScheduledSyncSection")

/** True when the settings page opened; a thrown launcher counts as unavailable and is logged. */
private fun launchBatterySettings(launcher: BatterySettingsLauncher): Boolean {
    val result = try {
        launcher.open()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        hintLogger.warn("Battery settings launcher threw", e)
        return false
    }
    if (result == BatterySettingsResult.Unavailable) hintLogger.warn("Battery settings page unavailable")
    return result == BatterySettingsResult.Launched
}

internal fun lineText(line: ScheduledSyncLine): String = statusLineText(line)

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
