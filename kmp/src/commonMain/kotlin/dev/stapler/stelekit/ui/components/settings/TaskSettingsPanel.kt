// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.git.ANDROID_MIN_INTERVAL_HELPER
import dev.stapler.stelekit.git.BACK_TO_DEFAULT
import dev.stapler.stelekit.git.COULDNT_READ_SETTINGS
import dev.stapler.stelekit.git.COULDNT_SAVE
import dev.stapler.stelekit.git.LOADING_SETTINGS_REASON
import dev.stapler.stelekit.git.UNMETERED_DESKTOP_REASON
import dev.stapler.stelekit.git.aboutEveryPhrase
import dev.stapler.stelekit.git.clampSummary
import dev.stapler.stelekit.git.firstCheckText
import dev.stapler.stelekit.git.intervalLabel
import dev.stapler.stelekit.git.isUnmeteredSelectable
import dev.stapler.stelekit.git.networkLabel
import dev.stapler.stelekit.git.scheduledIntervalPresets
import dev.stapler.stelekit.git.showsMinIntervalHelper
import dev.stapler.stelekit.git.spreadLabel
import dev.stapler.stelekit.git.spreadSummary
import dev.stapler.stelekit.jobs.schedule.JitterPreset
import dev.stapler.stelekit.jobs.schedule.NetworkPolicy
import dev.stapler.stelekit.jobs.schedule.NetworkPolicyCopy
import dev.stapler.stelekit.jobs.schedule.Schedule
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskScheduleInfo
import kotlinx.coroutines.launch

internal const val ADVANCED_LABEL = "Advanced"
internal const val SPREAD_LABEL = "Spread out start times"
internal const val SPREAD_HELPER = "Avoids every device syncing at the same minute."
internal const val INTERVAL_GROUP_LABEL = "How often"
internal const val NETWORK_GROUP_LABEL = "Network"
internal const val RESET_LABEL = "Reset to default"
internal const val RETRY_LABEL = "Retry"
private const val TARGET_DP = 48

/** Where the S2 panel's changes go: one (kind, graph) entry, or the kind's "Default for new graphs" record. */
sealed interface PanelTarget {
    data object Entry : PanelTarget
    data class Defaults(val followingGraphIds: List<String>) : PanelTarget
}

/**
 * Why a graph's "Sync automatically" switch cannot be turned on, with the existing fix action. An
 * already-on switch ignores it so the user can always turn sync off (UX-4).
 */
sealed interface SwitchGate {
    val reason: String
    val actionLabel: String
    val onFix: () -> Unit

    data class NoRemote(override val onFix: () -> Unit) : SwitchGate {
        override val reason = NO_REMOTE_REASON
        override val actionLabel = SET_UP_GIT_LABEL
    }

    data class FirstSyncReview(override val onFix: () -> Unit) : SwitchGate {
        override val reason = REVIEW_FIRST_SYNC_REASON
        override val actionLabel = REVIEW_FIRST_SYNC_LABEL
    }
}

internal const val SET_UP_GIT_LABEL = "Set up Git sync"
internal const val REVIEW_FIRST_SYNC_LABEL = "Review first sync"

private sealed interface PanelLoad {
    data object Loading : PanelLoad
    data object Failed : PanelLoad
    data class Ready(
        val enabled: Boolean,
        val schedule: Schedule,
        val network: NetworkPolicy,
        val hasOverrides: Boolean,
        val info: TaskScheduleInfo?,
    ) : PanelLoad
}

private enum class ChangedControl { Switch, Interval, Network, Spread, Reset }

/**
 * S2: one (kind, graph) task's settings. Changes are written through [controller] and the panel
 * then re-reads the store, so a failed save shows the stored value again with [COULDNT_SAVE] under
 * the control that changed. One-shot kinds show the network choice only.
 */
@Composable
fun TaskSettingsPanel(
    controller: BackgroundTaskSettingsController,
    taskKey: TaskKey,
    title: String,
    modifier: Modifier = Modifier,
    target: PanelTarget = PanelTarget.Entry,
    switchGate: SwitchGate? = null,
    /** Extra helper lines shown under the switch, e.g. the SAF fetch-only limit. */
    notes: List<String> = emptyList(),
    onChange: () -> Unit = {},
) {
    val caps = controller.capabilities
    val oneShot = controller.isOneShot(taskKey.kind)
    val isDefaults = target is PanelTarget.Defaults
    var load by remember(taskKey, target) { mutableStateOf<PanelLoad>(PanelLoad.Loading) }
    var reloadToken by remember(taskKey, target) { mutableIntStateOf(0) }
    var failed by remember(taskKey, target) { mutableStateOf<ChangedControl?>(null) }
    var firstCheck by remember(taskKey, target) { mutableStateOf<Int?>(null) }
    var resetDone by remember(taskKey, target) { mutableStateOf(false) }
    var advancedOpen by remember(taskKey, target) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(taskKey, target, reloadToken) {
        load = loadPanel(controller, taskKey, target)
    }

    fun submit(control: ChangedControl, edit: PanelEdit) {
        scope.launch {
            val result = when (target) {
                PanelTarget.Entry -> controller.edit(taskKey, edit)
                is PanelTarget.Defaults -> controller.editDefaults(taskKey.kind, edit, target.followingGraphIds)
            }
            failed = if (result == SaveResult.Failed) control else null
            resetDone = result == SaveResult.Saved && control == ChangedControl.Reset
            if (result == SaveResult.Saved && control == ChangedControl.Switch) {
                firstCheck = (edit as? PanelEdit.Enabled)?.takeIf { it.on }?.let { _ ->
                    val interval = (load as? PanelLoad.Ready)?.schedule?.intervalMinutes ?: caps.minIntervalMinutes
                    maxOf(interval, caps.minIntervalMinutes)
                }
            }
            reloadToken++
            if (result == SaveResult.Saved) onChange()
        }
    }

    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(taskKey, target) { runCatching { titleFocus.requestFocus() } }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.focusRequester(titleFocus).focusable().semantics { heading() }.padding(bottom = 8.dp),
        )
        when (val state = load) {
            PanelLoad.Loading -> LoadingSkeleton(showSwitch = !oneShot && !isDefaults)
            PanelLoad.Failed -> {
                Text(COULDNT_READ_SETTINGS, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Button(onClick = { reloadToken++ }, modifier = Modifier.heightIn(min = TARGET_DP.dp)) { Text(RETRY_LABEL) }
            }
            is PanelLoad.Ready -> {
                if (!oneShot && !isDefaults) {
                    SwitchBlock(state, caps.minIntervalMinutes, firstCheck, failed == ChangedControl.Switch, switchGate) {
                        submit(ChangedControl.Switch, PanelEdit.Enabled(it))
                    }
                    notes.forEach { Helper(it) }
                }
                if (!oneShot) {
                    IntervalBlock(state, controller, failed == ChangedControl.Interval) { submit(ChangedControl.Interval, PanelEdit.Interval(it)) }
                }
                NetworkBlock(state, controller, failed == ChangedControl.Network) { submit(ChangedControl.Network, PanelEdit.Network(it)) }
                if (!oneShot && caps.supportsJitter) {
                    AdvancedBlock(state, controller, advancedOpen, { advancedOpen = it }, failed == ChangedControl.Spread) {
                        submit(ChangedControl.Spread, PanelEdit.Spread(it))
                    }
                }
                if (!oneShot && !isDefaults && state.hasOverrides) {
                    TextButton(onClick = { submit(ChangedControl.Reset, PanelEdit.Reset) }, modifier = Modifier.heightIn(min = TARGET_DP.dp)) { Text(RESET_LABEL) }
                    SaveError(failed == ChangedControl.Reset)
                }
                if (resetDone) {
                    Text(BACK_TO_DEFAULT, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
    }
}

private suspend fun loadPanel(controller: BackgroundTaskSettingsController, key: TaskKey, target: PanelTarget): PanelLoad {
    if (target is PanelTarget.Defaults) {
        val base = controller.baseConfig(key.kind)
        return PanelLoad.Ready(base.enabled, base.schedule, base.constraints.network, hasOverrides = false, info = null)
    }
    return when (val loaded = controller.load(key)) {
        TaskSettingsLoad.ReadFailed -> PanelLoad.Failed
        is TaskSettingsLoad.Loaded -> PanelLoad.Ready(
            enabled = loaded.entry.enabled,
            schedule = loaded.resolved.schedule,
            network = loaded.resolved.constraints.network,
            hasOverrides = loaded.entry.overrides != null,
            info = if (controller.isOneShot(key.kind)) null else controller.describe(key) ?: inProcessInfo(controller, loaded.resolved.schedule),
        )
    }
}

/**
 * Git on an in-process adapter is driven by `PeriodicSyncDriver`, never `apply`, so `describe` has nothing
 * to report; that loop's gap is exactly interval to interval + spread, so it is derived here (and only here).
 */
private fun inProcessInfo(controller: BackgroundTaskSettingsController, schedule: Schedule): TaskScheduleInfo? {
    if (controller.capabilities.runsWhenClosed) return null
    val interval = schedule.intervalMinutes
    return TaskScheduleInfo(interval, clamped = false, nextRunAtMs = null, minGapMinutes = interval, maxGapMinutes = interval + schedule.jitterMinutes, appliesAfterNextRun = false)
}

@Composable
private fun SaveError(visible: Boolean) {
    if (!visible) return
    Text(
        COULDNT_SAVE,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(bottom = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun LoadingSkeleton(showSwitch: Boolean) {
    Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Loading settings" }) {
        if (showSwitch) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TARGET_DP.dp)
                    .toggleable(value = false, enabled = false, role = Role.Switch, onValueChange = {})
                    .semantics { stateDescription = LOADING_SETTINGS_REASON },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(SWITCH_LABEL, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                SkeletonBar(width = 40)
            }
        }
        repeat(SKELETON_ROWS) { SkeletonBar(width = SKELETON_WIDE_DP, modifier = Modifier.padding(vertical = 6.dp)) }
        Text(LOADING_SETTINGS_REASON, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val SKELETON_ROWS = 4
private const val SKELETON_WIDE_DP = 220

@Composable
private fun SkeletonBar(width: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier.width(width.dp).height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant))
}

@Composable
private fun SwitchBlock(
    state: PanelLoad.Ready,
    minInterval: Int,
    firstCheck: Int?,
    showError: Boolean,
    gate: SwitchGate?,
    onToggle: (Boolean) -> Unit,
) {
    Column {
        val blocked = gate != null && !state.enabled
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TARGET_DP.dp)
                .toggleable(value = state.enabled, enabled = !blocked, role = Role.Switch, onValueChange = onToggle)
                .semantics { if (blocked) stateDescription = "Disabled, ${gate?.reason}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(SWITCH_LABEL, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = state.enabled, onCheckedChange = null, enabled = !blocked)
        }
        if (blocked && gate != null) {
            Text(gate.reason, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = gate.onFix, modifier = Modifier.heightIn(min = TARGET_DP.dp)) { Text(gate.actionLabel) }
        }
        val effective = maxOf(state.schedule.intervalMinutes, minInterval)
        Text(
            text = "Runs ${aboutEveryPhrase(effective)}, ${networkLabel(state.network).lowercase()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.enabled && firstCheck != null) {
            Text(firstCheckText(firstCheck), style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        SaveError(showError)
    }
}

@Composable
private fun IntervalBlock(state: PanelLoad.Ready, controller: BackgroundTaskSettingsController, showError: Boolean, onPick: (Int) -> Unit) {
    Column {
        val caps = controller.capabilities
        val effective = maxOf(state.schedule.intervalMinutes, caps.minIntervalMinutes)
        val presets = scheduledIntervalPresets(caps)
        GroupLabel(INTERVAL_GROUP_LABEL)
        OptionGroup(
            options = presets.map { OptionSpec(intervalLabel(it), selected = it == effective, enabled = true) },
            onPick = { onPick(presets[it]) },
        )
        if (showsMinIntervalHelper(caps)) Helper(ANDROID_MIN_INTERVAL_HELPER) else if (!caps.runsWhenClosed) Helper(DESKTOP_HELPER)
        clampSummary(state.schedule.intervalMinutes, caps)?.let { Helper(it) }
        SaveError(showError)
    }
}

@Composable
private fun NetworkBlock(state: PanelLoad.Ready, controller: BackgroundTaskSettingsController, showError: Boolean, onPick: (NetworkPolicy) -> Unit) {
    Column {
        val caps = controller.capabilities
        val policies = NetworkPolicy.entries
        GroupLabel(NETWORK_GROUP_LABEL)
        OptionGroup(
            options = policies.map { policy ->
                val unavailable = policy == NetworkPolicy.Unmetered && !isUnmeteredSelectable(caps)
                OptionSpec(
                    label = networkLabel(policy),
                    selected = policy == state.network,
                    enabled = !unavailable,
                    reason = if (unavailable) UNMETERED_DESKTOP_REASON else null,
                    helper = if (policy == NetworkPolicy.Unmetered && !unavailable) NetworkPolicyCopy.UNMETERED_ONLY_HELPER else null,
                )
            },
            onPick = { onPick(policies[it]) },
        )
        SaveError(showError)
    }
}

@Composable
private fun AdvancedBlock(
    state: PanelLoad.Ready,
    controller: BackgroundTaskSettingsController,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    showError: Boolean,
    onPick: (JitterPreset) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TARGET_DP.dp)
                .selectable(selected = open, role = Role.Button, onClick = { onOpenChange(!open) })
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(ADVANCED_LABEL, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(if (open) "-" else "+", style = MaterialTheme.typography.bodyLarge)
        }
        if (!open) return
        val presets = JitterPreset.entries
        val current = JitterPreset.presetOf(state.schedule)
        GroupLabel(SPREAD_LABEL)
        OptionGroup(
            options = presets.map { OptionSpec(spreadLabel(it), selected = it == current, enabled = true) },
            onPick = { onPick(presets[it]) },
        )
        Helper(SPREAD_HELPER)
        spreadSummary(state.info, state.schedule, controller.capabilities)?.let { Helper(it) }
        SaveError(showError)
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
}

@Composable
private fun Helper(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal data class OptionSpec(
    val label: String,
    val selected: Boolean,
    val enabled: Boolean,
    val reason: String? = null,
    val helper: String? = null,
)

/**
 * A radio group: whole-row targets with `Role.RadioButton`, the inner button inert, and arrow keys that
 * move selection and focus to the next enabled option (Compose Desktop does not do this by itself).
 * A disabled option exposes its [OptionSpec.reason] through semantics so it reads "<name>, disabled, <reason>".
 */
@Composable
internal fun OptionGroup(options: List<OptionSpec>, onPick: (Int) -> Unit) {
    val requesters = remember(options.size) { List(options.size) { FocusRequester() } }
    var focused by remember(options.size) { mutableIntStateOf(-1) }
    val current by rememberUpdatedOptions(options)
    Column(
        modifier = Modifier.selectableGroup().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || focused !in options.indices) return@onPreviewKeyEvent false
            val step = when (event.key) {
                Key.DirectionDown, Key.DirectionRight -> 1
                Key.DirectionUp, Key.DirectionLeft -> -1
                Key.Spacebar -> {
                    if (current[focused].enabled) onPick(focused)
                    return@onPreviewKeyEvent current[focused].enabled
                }
                else -> return@onPreviewKeyEvent false
            }
            val next = nextEnabledIndex(current, focused, step) ?: return@onPreviewKeyEvent true
            onPick(next)
            requesters[next].requestFocus()
            true
        },
    ) {
        options.forEachIndexed { index, option ->
            OptionRow(option, onClick = { onPick(index) }, modifier = Modifier.focusRequester(requesters[index]).onFocusChanged { if (it.isFocused) focused = index })
        }
    }
}

@Composable
private fun rememberUpdatedOptions(options: List<OptionSpec>) = androidx.compose.runtime.rememberUpdatedState(options)

/** The next enabled index from [from] stepping by [step] (+1 or -1); null at the end of the list. */
internal fun nextEnabledIndex(options: List<OptionSpec>, from: Int, step: Int): Int? {
    var i = from + step
    while (i in options.indices) {
        if (options[i].enabled) return i
        i += step
    }
    return null
}

@Composable
private fun OptionRow(option: OptionSpec, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TARGET_DP.dp)
            .selectable(selected = option.selected, enabled = option.enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { option.reason?.let { stateDescription = "Disabled, $it" } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = option.selected, onClick = null, enabled = option.enabled)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(option.label, style = MaterialTheme.typography.bodyLarge)
            (option.reason ?: option.helper)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
