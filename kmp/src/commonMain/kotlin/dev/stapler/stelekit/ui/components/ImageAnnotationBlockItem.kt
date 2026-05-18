// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.CalibrationMethod

/**
 * Renders an `image_annotation` block as a clickable thumbnail with:
 *  - AsyncImage thumbnail (Coil 3, fills available width up to 200dp)
 *  - Measurement count badge overlaid on the bottom-right of the thumbnail
 *  - Compact metadata chips below the thumbnail for key block properties
 *
 * Tapping the thumbnail calls [onOpenAnnotationEditor] so the annotation editor
 * screen can be pushed onto the navigation stack.
 *
 * Block properties rendered as chips (sourced from [Block.properties]):
 *  - `image-measurement-count` — number of measurements
 *  - `image-calibration` — calibration method string
 *  - `image-unit` — display unit string
 */
@Composable
internal fun ImageAnnotationBlockItem(
    block: Block,
    onOpenAnnotationEditor: (imageAnnotationUuid: String) -> Unit,
    onStartEditing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageAnnotationUuid = block.properties["image-id"] ?: ""
    val imagePath = extractImagePath(block.content)
    val measurementCount = block.properties["image-measurement-count"]?.toIntOrNull() ?: 0
    val calibrationRaw = block.properties["image-calibration"] ?: CalibrationMethod.NONE.name
    val unit = block.properties["image-unit"]

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Thumbnail with measurement count badge
        Box(
            modifier = Modifier
                .heightIn(max = 200.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .semantics {
                    contentDescription = "Open annotation editor"
                    role = Role.Button
                }
                .clickable {
                    if (imageAnnotationUuid.isNotBlank()) {
                        onOpenAnnotationEditor(imageAnnotationUuid)
                    } else {
                        onStartEditing()
                    }
                },
        ) {
            val imageLoader = rememberSteleKitImageLoader()
            AsyncImage(
                model = imagePath,
                contentDescription = "Annotated image",
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(),
            )

            // Measurement count badge — bottom-right corner
            if (measurementCount > 0) {
                Badge(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = "$measurementCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        // Compact metadata chips row
        if (calibrationRaw.isNotBlank() || unit != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Calibration chip
                val calibrationLabel = calibrationRaw.replace('_', ' ').lowercase()
                    .replaceFirstChar { it.uppercase() }
                val calibrationColor = calibrationChipColor(calibrationRaw)
                MetadataChip(
                    label = calibrationLabel,
                    containerColor = calibrationColor.copy(alpha = 0.15f),
                    contentColor = calibrationColor,
                )

                // Unit chip
                if (unit != null) {
                    MetadataChip(label = unit)
                }

                // Measurement count chip (when not zero)
                if (measurementCount > 0) {
                    MetadataChip(label = "$measurementCount measurement${if (measurementCount != 1) "s" else ""}")
                }
            }
        }
    }
}

@Composable
private fun MetadataChip(
    label: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = containerColor,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Maps a [CalibrationMethod] name string to a representative color for the chip.
 */
@Composable
private fun calibrationChipColor(calibrationMethodName: String): Color {
    return when (calibrationMethodName.uppercase()) {
        CalibrationMethod.BLE_LASER.name,
        CalibrationMethod.MANUAL_REFERENCE.name,
        CalibrationMethod.LIDAR_DEPTH.name -> Color(0xFF2E7D32) // green
        CalibrationMethod.ARCORE_DEPTH.name -> Color(0xFFF57F17) // amber
        CalibrationMethod.EXIF_FOCAL.name -> Color(0xFFE65100) // deep orange
        CalibrationMethod.MONOCULAR_ML.name -> Color(0xFFB71C1C) // red
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * Extracts the image URL/path from a Logseq-format image markdown string.
 *
 * Handles `![alt](path)` syntax. Returns the raw content if the pattern is not found.
 */
private fun extractImagePath(content: String): String {
    val match = Regex("""!\[.*?]\((.+?)\)""").find(content)
    return match?.groupValues?.getOrNull(1) ?: content
}
