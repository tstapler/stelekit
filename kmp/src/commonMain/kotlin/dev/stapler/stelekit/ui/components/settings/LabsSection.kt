// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

const val HOME_SKETCH_LABEL = "Home project sketching (experimental)"

const val HOME_SKETCH_DISCLOSURE =
    "Turning this on enables the sketch screen. An optional project digest page and optional " +
        "LLM analysis are separate opt-ins, each off until you enable it per graph. Nothing leaves " +
        "this device unless you separately enable the LLM opt-in."

/** Experimental features, each behind its own switch; [onHomeSketchToggle] persists the flag. */
@Composable
fun LabsSection(
    homeSketchEnabled: Boolean,
    onHomeSketchToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SettingsSection("Labs") {
            SettingsToggleRow(
                label = HOME_SKETCH_LABEL,
                checked = homeSketchEnabled,
                onCheckedChange = onHomeSketchToggle,
            )
            if (homeSketchEnabled) {
                Text(
                    text = HOME_SKETCH_DISCLOSURE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }
        }
    }
}
