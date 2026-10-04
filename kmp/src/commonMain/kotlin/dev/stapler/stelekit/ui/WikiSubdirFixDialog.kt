// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.diagnostics.DirectoryScanResult
import kotlinx.serialization.Serializable

/**
 * Three-way decision dialog for wiki subdirectory mismatches.
 *
 * Presents Move/Merge/Leave-as-is options for resolving detected
 * subdirectory mismatches (e.g., when "Notes subfolder" is configured but the
 * actual content is in a different location).
 */
@Composable
fun WikiSubdirFixDialog(
    onDismiss: () -> Unit,
    onMove: (DryRunPreview) -> Unit,
    onMerge: (DryRunPreview) -> Unit,
    onLeave: () -> Unit,
    mismatchInfo: DirectoryScanResult,
    configuredPath: String = "",
    dryRunPreview: DryRunPreview = DryRunPreview(),
) {
    val configuredLabel = configuredPath.ifBlank { "(Root)" }
    val foundLabel = mismatchInfo.name.ifBlank { mismatchInfo.path }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        title = {
            Column {
                Text(
                    text = "Wiki Content Location Mismatch",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Configured: $configuredLabel | Found: $foundLabel",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Choose how to resolve the detected mismatch:",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                // Move option (red, high risk)
                OptionCardLarge(
                    title = "Move",
                    description = "Relocate all content from $foundLabel to $configuredLabel",
                    warningText = "WARNING: This action will move files and cannot be undone unless you have backups.",
                    icon = Icons.Filled.ArrowForward,
                    color = MaterialTheme.colorScheme.error,
                    isDangerous = true,
                    onClick = { onMove(dryRunPreview) },
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                // Merge option (yellow, medium risk)
                OptionCardLarge(
                    title = "Merge",
                    description = "Create $configuredLabel and copy content from $foundLabel (existing location preserved)",
                    warningText = "INFO: This will copy files, leaving the original location intact for future use.",
                    icon = Icons.Filled.Merge,
                    color = MaterialTheme.colorScheme.secondary,
                    isDangerous = false,
                    onClick = { onMerge(dryRunPreview) },
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                // Leave as-is option (green, safe)
                OptionCardLarge(
                    title = "Leave as-is",
                    description = "Keep content in ${mismatchInfo.name}, use ${mismatchInfo.path} for new content",
                    warningText = "This is the safest option - no files will be moved or copied.",
                    icon = Icons.Filled.CheckCircle,
                    color = MaterialTheme.colorScheme.tertiary,
                    isDangerous = false,
                    onClick = { onLeave() },
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                if (dryRunPreview.hasChanges) {
                    Text(
                        text = "Files that will be affected:",
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp, top = 16.dp),
                    )

                    for (change in dryRunPreview.changes) {
                        ChangeItem(change = change)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Option card for detailed selection.
 */
@Composable
fun OptionCardLarge(
    title: String,
    description: String,
    warningText: String,
    icon: ImageVector,
    color: Color,
    isDangerous: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isDangerous) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDangerous) 4.dp else 2.dp),
        border = if (isDangerous) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = color,
                    modifier = Modifier.padding(end = 12.dp),
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )

                    Text(
                        text = description,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Text(
                text = warningText,
                fontSize = 11.sp,
                color = if (isDangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontWeight = if (isDangerous) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * Item representing a file change in dry-run preview.
 */
@Composable
fun ChangeItem(
    change: FileChange,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (change.type == ChangeType.MOVE) Icons.Filled.ArrowForward else Icons.Filled.FileCopy,
            contentDescription = change.description,
            modifier = Modifier.padding(end = 8.dp),
            tint = when (change.type) {
                ChangeType.MOVE -> MaterialTheme.colorScheme.error
                ChangeType.COPY -> MaterialTheme.colorScheme.secondary
                ChangeType.DELETE -> MaterialTheme.colorScheme.error
                ChangeType.CREATE -> MaterialTheme.colorScheme.tertiary
            },
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = change.sourcePath,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )

            if (change.type == ChangeType.MOVE || change.type == ChangeType.COPY) {
                Text(
                    text = "→ ${change.targetPath}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            }
        }

        ChangeTypeBadge(change.type)
    }
}

/**
 * Badge showing the type of file change.
 */
@Composable
fun ChangeTypeBadge(
    type: ChangeType,
) {
    val (text, color) = when (type) {
        ChangeType.MOVE -> Pair("MOVE", MaterialTheme.colorScheme.error)
        ChangeType.COPY -> Pair("COPY", MaterialTheme.colorScheme.secondary)
        ChangeType.DELETE -> Pair("DELETE", MaterialTheme.colorScheme.error)
        ChangeType.CREATE -> Pair("CREATE", MaterialTheme.colorScheme.tertiary)
    }

    Text(
        text = text,
        fontSize = 10.sp,
        color = color,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/**
 * Dry-run preview of file changes that will occur.
 */
@Serializable
data class DryRunPreview(
    val hasChanges: Boolean = false,
    val changes: List<FileChange> = emptyList(),
    val estimatedSize: Long = 0L,
    val estimatedTime: Long = 0L,
)

/**
 * Represents a single file change in dry-run preview.
 */
@Serializable
data class FileChange(
    val type: ChangeType,
    val sourcePath: String,
    val targetPath: String,
    val description: String,
    val estimatedSize: Long = 0L,
    val isRisky: Boolean = false,
)

/**
 * Type of file change.
 */
enum class ChangeType {
    MOVE, COPY, DELETE, CREATE
}

/**
 * Banner for displaying wiki subdirectory content mismatch information
 */
@Composable
fun WikiSubdirFixBanner(
    mismatchInfo: DirectoryScanResult,
    configuredPath: String = "",
    dryRunPreview: DryRunPreview = DryRunPreview(),
    onDismiss: () -> Unit,
    onResolveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "Content mismatch detected",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 12.dp),
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Wiki Content Location Mismatch Detected",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )

                    val configuredLabel = configuredPath.ifBlank { "(Root)" }
                    val foundLabel = mismatchInfo.name.ifBlank { mismatchInfo.path }
                    val detailText = if (configuredPath.isNotBlank() && configuredPath != foundLabel) {
                        "Configured notes folder expects content at $configuredLabel, but found in '$foundLabel'"
                    } else {
                        "No notes found at configured location — found notes in '$foundLabel' instead"
                    }
                    Text(
                        text = detailText,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                    )
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("Dismiss", color = MaterialTheme.colorScheme.error)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dryRunPreview.hasChanges) {
                    Text(
                        text = "This will affect ${dryRunPreview.changes.size} files.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                } else {
                    Text("")
                }

                TextButton(
                    onClick = onResolveClick,
                ) {
                    Text("Resolve Now", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}