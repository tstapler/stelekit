package dev.stapler.stelekit.ui.assets

import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.ui.components.rememberSteleKitImageLoader
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailScreen(
    viewModel: AssetDetailViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToPage: (pageUuid: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.asset?.filePath?.substringAfterLast('/') ?: "Asset Detail",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = uiState.error ?: "Unknown error",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            uiState.asset != null -> {
                AssetDetailContent(
                    asset = uiState.asset!!,
                    onNavigateToPage = onNavigateToPage,
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                )
            }
        }
    }
}

@Composable
private fun AssetDetailContent(
    asset: AssetEntry,
    onNavigateToPage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        item {
            if (asset.mediaType == AssetMediaType.IMAGE) {
                ImageViewer(asset = asset)
            } else {
                NonImageTypeIcon(asset = asset)
            }
        }
        item {
            AssetMetadataCard(
                asset = asset,
                onNavigateToPage = onNavigateToPage,
            )
        }
    }
}

@Composable
private fun ImageViewer(asset: AssetEntry, modifier: Modifier = Modifier) {
    val imageLoader = rememberSteleKitImageLoader()
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offsetX += panChange.x
        offsetY += panChange.y
    }
    val coilModel = when {
        asset.relativePath.startsWith("../assets/") -> asset.relativePath
        asset.filePath.startsWith("saf://") -> asset.filePath
        asset.filePath.startsWith("file://") || asset.filePath.startsWith("content://") -> asset.filePath
        else -> "file://${asset.filePath}"
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .transformable(transformableState),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = coilModel,
            imageLoader = imageLoader,
            contentDescription = asset.filePath.substringAfterLast('/'),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        )
    }
}

@Composable
private fun NonImageTypeIcon(asset: AssetEntry, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = assetIcon(asset.mediaType),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun AssetMetadataCard(
    asset: AssetEntry,
    onNavigateToPage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetadataRow(
                label = "File",
                value = asset.filePath.substringAfterLast('/'),
            )
            MetadataRow(
                label = "Size",
                value = formatFileSize(asset.sizeBytes),
            )
            MetadataRow(
                label = "Type",
                value = asset.mediaType.name.lowercase().replaceFirstChar { it.uppercaseChar() },
            )
            val importDate = Instant.fromEpochMilliseconds(asset.importedAtMs)
                .toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
            MetadataRow(
                label = "Imported",
                value = importDate,
            )

            if (asset.tags.isNotEmpty()) {
                Text(
                    text = "Tags",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(asset.tags) { tag ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(tag) },
                        )
                    }
                }
            }

            if (!asset.ocrText.isNullOrBlank()) {
                var ocrExpanded by remember { mutableStateOf(false) }
                Column {
                    TextButton(onClick = { ocrExpanded = !ocrExpanded }) {
                        Text(if (ocrExpanded) "Hide OCR Text" else "Show OCR Text")
                    }
                    if (ocrExpanded) {
                        Text(
                            text = asset.ocrText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (asset.pageUuids.isNotEmpty()) {
                Text(
                    text = "Pages",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(asset.pageUuids) { uuid ->
                        AssistChip(
                            onClick = { onNavigateToPage(uuid) },
                            label = { Text("Page ${uuid.take(8)}") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Article,
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1_048_576L -> "${bytes / 1024} KB"
    else -> "${bytes / 1_048_576} MB"
}
