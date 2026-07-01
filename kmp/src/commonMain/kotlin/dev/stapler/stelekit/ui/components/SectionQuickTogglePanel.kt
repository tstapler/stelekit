package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sections.SectionManifest
import dev.stapler.stelekit.sections.SectionState

/**
 * Quick-access panel showing all ACTIVE and HIDDEN sections with Active/Hidden toggles.
 * Does NOT expose or allow toggling REMOVED sections.
 * "Manage" navigates to Settings -> Sections.
 */
@Composable
fun SectionQuickTogglePanel(
    visible: Boolean,
    manifest: SectionManifest,
    sectionStates: Map<String, SectionState>,
    onToggleSection: (sectionId: String, newState: SectionState) -> Unit,
    onManageSections: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sections") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                manifest.sections.forEach { section ->
                    val state = sectionStates[section.id] ?: SectionState.ACTIVE
                    // Never expose REMOVED sections from this panel
                    if (state == SectionState.REMOVED) return@forEach

                    val dotColor = section.color?.let {
                        try { Color(it.trimStart('#').toLong(16) or 0xFF000000L) }
                        catch (_: NumberFormatException) { MaterialTheme.colorScheme.secondary }
                    } ?: MaterialTheme.colorScheme.secondary

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.size(10.dp).clip(CircleShape),
                            color = dotColor,
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = section.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = state == SectionState.ACTIVE,
                            onCheckedChange = { active ->
                                onToggleSection(
                                    section.id,
                                    if (active) SectionState.ACTIVE else SectionState.HIDDEN,
                                )
                            },
                        )
                    }
                }
                if (manifest.sections.all { (sectionStates[it.id] ?: SectionState.ACTIVE) == SectionState.REMOVED }) {
                    Text(
                        text = "No sections are currently available on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onManageSections(); onDismiss() }) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Manage")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
