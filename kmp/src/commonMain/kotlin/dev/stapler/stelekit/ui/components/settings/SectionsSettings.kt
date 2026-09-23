package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sections.SectionDefinition
import dev.stapler.stelekit.sections.SectionManifest
import dev.stapler.stelekit.sections.SectionState

/**
 * Settings panel for managing section definitions.
 * Lists sections with edit/delete, and a button to create new ones.
 */
@Composable
fun SectionsSettings(
    manifest: SectionManifest,
    onCreateSection: (id: String, displayName: String, color: String?, pagePathPrefix: String, journalPathPrefix: String) -> Unit,
    onRenameSection: (id: String, newDisplayName: String) -> Unit,
    onDeleteSection: (id: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var sectionToRename by remember { mutableStateOf<SectionDefinition?>(null) }
    var sectionToDelete by remember { mutableStateOf<SectionDefinition?>(null) }

    SettingsSection("Sections") {
        if (manifest.sections.isEmpty()) {
            Text(
                text = "No sections defined. Add a section to organize your notes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            manifest.sections.forEach { section ->
                SectionRow(
                    section = section,
                    onRename = { sectionToRename = section },
                    onDelete = { sectionToDelete = section },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { showCreateDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("New Section")
        }
    }

    if (showCreateDialog) {
        CreateSectionDialog(
            onConfirm = { id, name, color, pagePfx, journalPfx ->
                onCreateSection(id, name, color, pagePfx, journalPfx)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    sectionToRename?.let { section ->
        RenameSectionDialog(
            section = section,
            onConfirm = { newName ->
                onRenameSection(section.id, newName)
                sectionToRename = null
            },
            onDismiss = { sectionToRename = null },
        )
    }

    sectionToDelete?.let { section ->
        AlertDialog(
            onDismissRequest = { sectionToDelete = null },
            title = { Text("Delete Section") },
            text = {
                Text("Delete \"${section.displayName}\"? Pages in this section will remain but lose their section assignment.")
            },
            confirmButton = {
                TextButton(onClick = { onDeleteSection(section.id); sectionToDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { sectionToDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Panel for controlling per-device section subscriptions (Active or Hidden only).
 * REMOVED state is reserved for DeviceSetupWizard.
 * Shows a "Restart required" banner when any toggle is changed.
 */
@Composable
fun DeviceSubscriptionsPanel(
    manifest: SectionManifest,
    sectionStates: Map<String, SectionState>,
    onToggleSection: (sectionId: String, newState: SectionState) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRestartBanner by remember { mutableStateOf(false) }

    SettingsSection("Device Subscriptions") {
        Text(
            text = "Control which sections are loaded on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (showRestartBanner) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(
                    text = "Restart required for subscription changes to take effect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        manifest.sections.forEach { section ->
            val state = sectionStates[section.id] ?: SectionState.ACTIVE
            if (state == SectionState.REMOVED) return@forEach
            SettingsRow(label = section.displayName) {
                Switch(
                    checked = state == SectionState.ACTIVE,
                    onCheckedChange = { active ->
                        val newState = if (active) SectionState.ACTIVE else SectionState.HIDDEN
                        onToggleSection(section.id, newState)
                        showRestartBanner = true
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionRow(
    section: SectionDefinition,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val dotColor = remember(section.color) {
        section.color?.let {
            try { Color(it.trimStart('#').toLong(16) or 0xFF000000L) }
            catch (_: NumberFormatException) { null }
        }
    } ?: MaterialTheme.colorScheme.secondary

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(12.dp).clip(CircleShape),
            color = dotColor,
        ) {}
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = section.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "pages: ${section.pagePathPrefix}  journals: ${section.journalPathPrefix}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRename) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Rename section",
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete section",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun CreateSectionDialog(
    onConfirm: (id: String, displayName: String, color: String?, pagePathPrefix: String, journalPathPrefix: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var displayName by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("") }
    var pagePathPrefix by remember { mutableStateOf("") }
    var journalPathPrefix by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Section") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { v ->
                        displayName = v
                        error = null
                        if (pagePathPrefix.isEmpty()) pagePathPrefix = slugify(v)
                        if (journalPathPrefix.isEmpty()) journalPathPrefix = "journals-${slugify(v)}"
                    },
                    label = { Text("Display name") },
                    isError = error != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pagePathPrefix,
                    onValueChange = { pagePathPrefix = it },
                    label = { Text("Pages folder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = journalPathPrefix,
                    onValueChange = { journalPathPrefix = it },
                    label = { Text("Journals folder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = color,
                    onValueChange = { color = it },
                    label = { Text("Color (optional, e.g. #3B82F6)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = displayName.trim()
                if (trimmed.isEmpty()) { error = "Display name is required"; return@TextButton }
                val id = slugify(trimmed)
                if (id.isEmpty()) { error = "Could not generate ID from name"; return@TextButton }
                if (pagePathPrefix.isBlank()) { error = "Pages folder is required"; return@TextButton }
                if (journalPathPrefix.isBlank()) { error = "Journals folder is required"; return@TextButton }
                onConfirm(id, trimmed, color.trim().takeIf { it.isNotEmpty() }, pagePathPrefix.trim(), journalPathPrefix.trim())
            }) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RenameSectionDialog(
    section: SectionDefinition,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var displayName by remember { mutableStateOf(section.displayName) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Section") },
        text = {
            Column {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it; error = null },
                    label = { Text("Display name") },
                    isError = error != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = displayName.trim()
                if (trimmed.isEmpty()) { error = "Name is required"; return@TextButton }
                onConfirm(trimmed)
            }) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun slugify(name: String): String =
    name.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
