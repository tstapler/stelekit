// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.tags.TagSettings

@Composable
fun TagSuggestionSettings(
    tagSettings: TagSettings,
    hasLlmKey: Boolean,
    modifier: Modifier = Modifier,
) {
    var enabled by remember { mutableStateOf(tagSettings.isEnabled()) }
    var llmTierEnabled by remember { mutableStateOf(tagSettings.isLlmTierEnabled()) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Auto-Tag Suggestions",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Suggest wiki-link tags based on block content.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Enable tag suggestions",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Show suggested wiki-links via context menu and voice capture",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { newVal ->
                    enabled = newVal
                    tagSettings.setEnabled(newVal)
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "LLM tag enrichment",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (!enabled || !hasLlmKey) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (!hasLlmKey) "Requires a configured provider in Settings → AI Providers (remote key or on-device)"
                           else "Use the configured LLM for deeper tag suggestions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = llmTierEnabled && hasLlmKey,
                enabled = enabled && hasLlmKey,
                onCheckedChange = { newVal ->
                    llmTierEnabled = newVal
                    tagSettings.setLlmTierEnabled(newVal)
                },
            )
        }
    }
}
