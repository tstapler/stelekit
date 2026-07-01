package dev.stapler.stelekit.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sections.SectionDefinition
import dev.stapler.stelekit.sections.SectionManifest
import dev.stapler.stelekit.sections.SectionState

/**
 * One-time wizard shown when a graph with sections is first opened on a device.
 * Three paths: Work (primary section, others REMOVED), Personal (all ACTIVE), Custom.
 * Gated by deviceSetupComplete flag in PlatformSettings — the ViewModel reads it and
 * passes it as [manifest]; the wizard calls [onComplete] with the chosen configuration.
 */
@Composable
fun DeviceSetupWizard(
    manifest: SectionManifest,
    onComplete: (defaultSection: String, sectionStates: Map<String, SectionState>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf<SetupMode?>(null) }
    var primarySection by remember(manifest) { mutableStateOf(manifest.sections.firstOrNull()?.id ?: "") }
    var customStates by remember(manifest) {
        mutableStateOf(manifest.sections.associate { it.id to SectionState.ACTIVE })
    }
    var customDefaultSection by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Up This Device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (mode) {
                    null -> ModeSelectionContent(
                        onWorkMode = { mode = SetupMode.WORK },
                        onPersonalMode = {
                            onComplete(
                                "",
                                manifest.sections.associate { it.id to SectionState.ACTIVE },
                            )
                        },
                        onCustomMode = { mode = SetupMode.CUSTOM },
                    )

                    SetupMode.WORK -> WorkModeContent(
                        sections = manifest.sections.map { it.id to it.displayName },
                        primarySection = primarySection,
                        onPrimaryChange = { primarySection = it },
                    )

                    SetupMode.CUSTOM -> CustomModeContent(
                        sections = manifest.sections,
                        customStates = customStates,
                        onStateChange = { id, state -> customStates = customStates + (id to state) },
                        customDefaultSection = customDefaultSection,
                        onDefaultChange = { customDefaultSection = it },
                    )
                }
            }
        },
        confirmButton = {
            when (mode) {
                null -> { /* inline buttons handle actions */ }
                SetupMode.WORK -> TextButton(onClick = {
                    val states = manifest.sections.associate { section ->
                        section.id to if (section.id == primarySection) SectionState.ACTIVE else SectionState.REMOVED
                    }
                    onComplete(primarySection, states)
                }) { Text("Continue") }
                SetupMode.CUSTOM -> TextButton(onClick = {
                    onComplete(customDefaultSection, customStates)
                }) { Text("Continue") }
            }
        },
        dismissButton = {
            if (mode != null) {
                TextButton(onClick = { mode = null }) { Text("Back") }
            } else {
                TextButton(onClick = onDismiss) { Text("Skip") }
            }
        },
    )
}

@Composable
private fun ModeSelectionContent(
    onWorkMode: () -> Unit,
    onPersonalMode: () -> Unit,
    onCustomMode: () -> Unit,
) {
    Text("How will you use SteleKit on this device?", style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(onClick = onWorkMode, modifier = Modifier.fillMaxWidth()) {
        Text("Work device — primary section only")
    }
    OutlinedButton(onClick = onPersonalMode, modifier = Modifier.fillMaxWidth()) {
        Text("Personal device — all sections active")
    }
    OutlinedButton(onClick = onCustomMode, modifier = Modifier.fillMaxWidth()) {
        Text("Custom")
    }
}

@Composable
private fun WorkModeContent(
    sections: List<Pair<String, String>>,
    primarySection: String,
    onPrimaryChange: (String) -> Unit,
) {
    Text("Pick your primary section:", style = MaterialTheme.typography.bodyMedium)
    sections.forEach { (id, displayName) ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = primarySection == id,
                onClick = { onPrimaryChange(id) },
            )
            Text(displayName, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun CustomModeContent(
    sections: List<SectionDefinition>,
    customStates: Map<String, SectionState>,
    onStateChange: (String, SectionState) -> Unit,
    customDefaultSection: String,
    onDefaultChange: (String) -> Unit,
) {
    Text("Section access:", style = MaterialTheme.typography.bodyMedium)
    sections.forEach { section ->
        val state = customStates[section.id] ?: SectionState.ACTIVE
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(section.displayName, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val next = when (state) {
                    SectionState.ACTIVE -> SectionState.HIDDEN
                    SectionState.HIDDEN -> SectionState.REMOVED
                    SectionState.REMOVED -> SectionState.ACTIVE
                }
                onStateChange(section.id, next)
            }) {
                Text(state.name.lowercase().replaceFirstChar { it.uppercase() })
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text("Default section (for new pages):", style = MaterialTheme.typography.bodyMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = customDefaultSection.isEmpty(), onClick = { onDefaultChange("") })
        Text("Global", style = MaterialTheme.typography.bodyLarge)
    }
    sections.forEach { section ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = customDefaultSection == section.id,
                onClick = { onDefaultChange(section.id) },
            )
            Text(section.displayName, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private enum class SetupMode { WORK, CUSTOM }
