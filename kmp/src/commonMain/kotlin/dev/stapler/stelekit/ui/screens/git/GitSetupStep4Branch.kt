// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun Step4Branch(
    remoteBranch: String,
    onRemoteBranchChange: (String) -> Unit,
    pollIntervalMinutes: Int,
    onPollIntervalChange: (Int) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sync settings", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = remoteBranch,
            onValueChange = onRemoteBranchChange,
            label = { Text("Remote branch") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Text("Background poll interval", style = MaterialTheme.typography.labelMedium)

        val intervals = listOf(0 to "Off", 5 to "5 minutes", 15 to "15 minutes", 30 to "30 minutes", 60 to "1 hour")
        intervals.forEach { (minutes, label) ->
            Row(
                modifier = Modifier.fillMaxWidth().selectable(selected = pollIntervalMinutes == minutes, role = Role.RadioButton, onClick = { onPollIntervalChange(minutes) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = pollIntervalMinutes == minutes,
                    onClick = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(label)
            }
        }

        GitSetupNavRow(onBack = onBack, onNext = onNext)
    }
}
