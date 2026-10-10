// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The "Back" / "Next" button row shared by Steps 2–4 (Step 1 has only a single "Next"; Step 5's Back/Save row has its own disabled-state logic). */
@Composable
internal fun GitSetupNavRow(
    onBack: () -> Unit,
    onNext: () -> Unit,
    nextEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OutlinedButton(onClick = onBack) { Text("Back") }
        Button(onClick = onNext, enabled = nextEnabled) { Text("Next") }
    }
}
