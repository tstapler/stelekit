// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.jobs.schedule.NetworkPolicyCopy

/** Everything [LfsTransferSection] shows and can change; the stack builds it. */
data class LfsTransferSettingsState(
    val maxObjectMb: Int,
    val deferOnMetered: Boolean,
    /** False where the platform cannot tell a metered connection apart; the switch is then hidden. */
    val meteredDetectionSupported: Boolean,
    val onMaxObjectMbChange: (Int) -> Unit,
    val onDeferOnMeteredChange: (Boolean) -> Unit,
)

/** Largest-image choices, in MB. A stored value outside this list is shown as an extra choice so it is never hidden. */
internal val LFS_MAX_OBJECT_MB_OPTIONS = listOf(50, 100, 256, 512, 1024)
internal const val LFS_SIZE_HELPER = "Images larger than this stay on the server and are listed under Background jobs."
internal const val LFS_METERED_LABEL = NetworkPolicyCopy.UNMETERED_ONLY_LABEL
internal const val LFS_METERED_HELPER = NetworkPolicyCopy.UNMETERED_ONLY_HELPER
private const val MIN_TARGET_DP = 48

internal fun lfsSizeLabel(mb: Int): String = if (mb >= 1024 && mb % 1024 == 0) "${mb / 1024} GB" else "$mb MB"

/** Settings > Large files (Git LFS): the biggest image to download automatically, and whether to wait for an unmetered network. */
@Composable
fun LfsTransferSection(state: LfsTransferSettingsState, modifier: Modifier = Modifier) {
    SettingsSection("Large files (Git LFS)") {
        Column(modifier = modifier.fillMaxWidth()) {
            Text("Largest image to download automatically", style = MaterialTheme.typography.bodyMedium)
            Text(
                LFS_SIZE_HELPER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Column(modifier = Modifier.selectableGroup()) {
                (LFS_MAX_OBJECT_MB_OPTIONS + state.maxObjectMb).distinct().sorted().forEach { mb ->
                    SizeOption(mb, selected = mb == state.maxObjectMb) { state.onMaxObjectMbChange(mb) }
                }
            }
            if (state.meteredDetectionSupported) MeteredRow(state)
        }
    }
}

@Composable
private fun SizeOption(mb: Int, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TARGET_DP.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(lfsSizeLabel(mb), modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun MeteredRow(state: LfsTransferSettingsState) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MIN_TARGET_DP.dp)
                .padding(top = 8.dp)
                .toggleable(value = state.deferOnMetered, role = Role.Switch, onValueChange = state.onDeferOnMeteredChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(LFS_METERED_LABEL, modifier = Modifier.weight(1f))
            Switch(checked = state.deferOnMetered, onCheckedChange = null)
        }
        Text(
            LFS_METERED_HELPER,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
