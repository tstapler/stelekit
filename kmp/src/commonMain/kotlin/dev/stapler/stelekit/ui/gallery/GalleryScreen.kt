// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.ui.components.rememberSteleKitImageLoader

/**
 * Full-screen gallery view showing a staggered/fixed grid of image annotation thumbnails.
 *
 * Features:
 * - Horizontal lazy row of tag filter chips at the top
 * - Sort dropdown (by date captured / imported / measurement count)
 * - Fixed 2-column lazy grid of image cards
 * - Each card: thumbnail, tag chips, measurement count, calibration confidence badge
 * - Tap: navigates to [AnnotationEditorScreen] via [onOpenAnnotationEditor]
 * - Info / "Go to page": calls [onNavigateToPage] with the block's page UUID
 */
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onOpenAnnotationEditor: (imageAnnotationUuid: String) -> Unit,
    onNavigateToPage: (pageUuid: String) -> Unit,
    modifier: Modifier = Modifier,
    onImportImage: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Header row: title + sort button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Gallery",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            SortDropdownButton(
                current = state.sortOrder,
                onSelect = { viewModel.setSortOrder(it) },
            )
        }

        // Tag filter chips row
        if (state.availableTags.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                item {
                    FilterChip(
                        selected = state.selectedTag == null,
                        onClick = { viewModel.selectTag(null) },
                        label = { Text("All") },
                    )
                }
                items(state.availableTags) { tag ->
                    FilterChip(
                        selected = state.selectedTag == tag,
                        onClick = { viewModel.selectTag(if (state.selectedTag == tag) null else tag) },
                        label = { Text(tag) },
                    )
                }
            }
        }

        when {
            state.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.errorMessage != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Failed to load gallery: ${state.errorMessage}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            state.images.isEmpty() -> {
                GalleryEmptyState(
                    onCapturePhoto = { onImportImage?.invoke() ?: run { /* no-op if not wired */ } },
                    onImportPhoto = null, // photo picker wiring is future work
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.images, key = { it.uuid }) { image ->
                        GalleryCard(
                            image = image,
                            onTap = { onOpenAnnotationEditor(image.uuid) },
                            onGoToPage = { onNavigateToPage(image.pageUuid) },
                        )
                    }
                }
            }
        }
    }

    if (onImportImage != null && state.images.isNotEmpty()) {
        FloatingActionButton(
            onClick = onImportImage,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.AddAPhoto,
                contentDescription = "Capture image for annotation",
            )
        }
    }
    } // Box
}

// ── Gallery card ──────────────────────────────────────────────────────────────

@Composable
private fun GalleryCard(
    image: ImageAnnotation,
    onTap: () -> Unit,
    onGoToPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Annotated image — tap to open editor"
                role = Role.Button
            }
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            // Thumbnail
            val imageLoader = rememberSteleKitImageLoader()
            AsyncImage(
                model = image.thumbnailPath ?: image.filePath,
                contentDescription = "Annotated image",
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
            )

            // Show uncalibrated indicator if calibration method is NONE
            if (image.calibration.method == CalibrationMethod.NONE) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Tap to calibrate",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // Metadata section
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Calibration confidence badge
                CalibrationBadge(method = image.calibration.method)

                // Tag chips (max 2 shown inline)
                if (image.tags.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        image.tags.take(2).forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (image.tags.size > 2) {
                            Text(
                                text = "+${image.tags.size - 2}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // "Go to page" link
                TextButton(
                    onClick = onGoToPage,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    Text(
                        text = "Go to page",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

// ── Calibration badge ─────────────────────────────────────────────────────────

@Composable
private fun CalibrationBadge(method: CalibrationMethod) {
    val (label, color) = when (method) {
        CalibrationMethod.BLE_LASER -> "BLE Laser" to MaterialTheme.colorScheme.primary
        CalibrationMethod.MANUAL_REFERENCE -> "Manual" to MaterialTheme.colorScheme.primary
        CalibrationMethod.LIDAR_DEPTH -> "LiDAR" to MaterialTheme.colorScheme.primary
        CalibrationMethod.ARCORE_DEPTH -> "ARCore" to MaterialTheme.colorScheme.tertiary
        CalibrationMethod.EXIF_FOCAL -> "EXIF" to MaterialTheme.colorScheme.secondary
        CalibrationMethod.MONOCULAR_ML -> "AI Depth" to MaterialTheme.colorScheme.error
        CalibrationMethod.NONE -> "Uncalibrated" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ── Sort dropdown ─────────────────────────────────────────────────────────────

@Composable
private fun SortDropdownButton(
    current: GallerySortOrder,
    onSelect: (GallerySortOrder) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = "Sort gallery",
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            GallerySortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.displayName()) },
                    onClick = {
                        onSelect(order)
                        expanded = false
                    },
                    leadingIcon = {
                        if (order == current) {
                            RadioButton(selected = true, onClick = null)
                        }
                    },
                )
            }
        }
    }
}

private fun GallerySortOrder.displayName(): String = when (this) {
    GallerySortOrder.BY_DATE_CAPTURED -> "Date captured"
    GallerySortOrder.BY_DATE_IMPORTED -> "Date imported"
    GallerySortOrder.BY_MEASUREMENT_COUNT -> "Measurement count"
}
