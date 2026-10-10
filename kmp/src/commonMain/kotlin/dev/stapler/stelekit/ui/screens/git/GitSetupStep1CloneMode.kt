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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun Step1CloneMode(
    cloneMode: CloneMode,
    onCloneModeChange: (CloneMode) -> Unit,
    onNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Repository mode", style = MaterialTheme.typography.titleMedium)
        Text("Do you already have a local git clone of your notes repository?", style = MaterialTheme.typography.bodyMedium)

        Row(
            modifier = Modifier.fillMaxWidth().selectable(
                selected = cloneMode == CloneMode.UseExistingClone,
                role = Role.RadioButton,
                onClick = { onCloneModeChange(CloneMode.UseExistingClone) },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = cloneMode == CloneMode.UseExistingClone, onClick = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Use existing clone")
        }
        Row(
            modifier = Modifier.fillMaxWidth().selectable(
                selected = cloneMode == CloneMode.CloneNewRepository,
                role = Role.RadioButton,
                onClick = { onCloneModeChange(CloneMode.CloneNewRepository) },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = cloneMode == CloneMode.CloneNewRepository, onClick = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clone a remote repository")
        }

        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text("Next")
        }
    }
}
