// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.llm.LlmFeature
import dev.stapler.stelekit.llm.LlmProvider
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.llm.LlmSettings

private const val AUTO_LABEL = "Auto (recommended)"
private const val DISABLED_LABEL = "Disabled"

private fun LlmFeature.displayName(): String = when (this) {
    LlmFeature.VOICE_FORMATTING -> "Voice formatting"
    LlmFeature.TAG_SUGGESTION -> "Tag suggestion"
    LlmFeature.GRAPH_EDIT_SYNTHESIS -> "Graph edit synthesis"
}

/**
 * Epic 6 Story 6.4: dropdown of [LlmProviderRegistry.availableForFeature] for a single
 * [feature], plus an "Auto" option at the top — the Zed-style "one global-ish default +
 * explicit override" model (features research §1). One instance is rendered per [LlmFeature]
 * value inside the main settings screen, not as three separate screens.
 *
 * `GRAPH_EDIT_SYNTHESIS` excludes short-form-only providers (on-device) per Story 1.3's
 * `excludeShortFormOnly` param — their ~256-token output ceiling makes them unusable for
 * multi-block synthesis proposals.
 */
@Composable
fun PerFeatureProviderPicker(
    feature: LlmFeature,
    registry: LlmProviderRegistry,
    llmSettings: LlmSettings,
    modifier: Modifier = Modifier,
) {
    var availableProviders by remember(feature) { mutableStateOf<List<LlmProvider>?>(null) }
    var selectedId by remember(feature) { mutableStateOf(llmSettings.getSelectedProviderId(feature)) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(feature, registry) {
        availableProviders = registry.availableForFeature(
            feature = feature,
            excludeShortFormOnly = feature == LlmFeature.GRAPH_EDIT_SYNTHESIS,
        )
    }

    // MA5: DISABLED_SENTINEL must be checked explicitly and shown as its own label — without
    // this branch, a disabled feature falls through to the `?: AUTO_LABEL` default and renders
    // indistinguishably from "Auto", even though the two are semantically opposite (Auto uses
    // the first available provider; Disabled uses none).
    val selectedLabel = when (selectedId) {
        LlmProviderRegistry.DISABLED_SENTINEL -> DISABLED_LABEL
        else -> availableProviders?.firstOrNull { it.id == selectedId }?.displayName ?: AUTO_LABEL
    }

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(feature.displayName(), style = MaterialTheme.typography.bodyLarge)

        Column {
            OutlinedButton(onClick = { expanded = true }) {
                Text(selectedLabel)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(AUTO_LABEL) },
                    onClick = {
                        selectedId = null
                        llmSettings.setSelectedProviderId(feature, null)
                        expanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(DISABLED_LABEL) },
                    onClick = {
                        selectedId = LlmProviderRegistry.DISABLED_SENTINEL
                        llmSettings.setSelectedProviderId(feature, LlmProviderRegistry.DISABLED_SENTINEL)
                        expanded = false
                    },
                )
                availableProviders?.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.displayName) },
                        onClick = {
                            selectedId = provider.id
                            llmSettings.setSelectedProviderId(feature, provider.id)
                            expanded = false
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
