package dev.stapler.stelekit.ui.assets

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.ui.components.rememberSteleKitImageLoader
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.floor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailScreen(
    viewModel: AssetDetailViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToPage: (pageUuid: String) -> Unit,
    modifier: Modifier = Modifier,
    onAnnotate: (AssetEntry) -> Unit = {},
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
                actions = {
                    val asset = uiState.asset
                    if (asset != null && asset.mediaType == AssetMediaType.IMAGE && !asset.isOrphan) {
                        IconButton(onClick = { onAnnotate(asset) }) {
                            Icon(
                                imageVector = Icons.Default.Straighten,
                                contentDescription = "Annotate / measure image",
                            )
                        }
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

private enum class ViewerBackground(val label: String) {
    THEME("Theme"),
    WHITE("White"),
    BLACK("Black"),
    CHECKERBOARD("Checkered"),
}

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

@Composable
private fun ImageViewer(asset: AssetEntry, modifier: Modifier = Modifier) {
    val imageLoader = rememberSteleKitImageLoader()
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var background by remember { mutableStateOf(ViewerBackground.THEME) }

    fun maxPan(dimensionPx: Int): Float = (dimensionPx * (scale - 1f) / 2f).coerceAtLeast(0f)

    fun resetZoom() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
        offsetX = (offsetX + panChange.x).coerceIn(-maxPan(containerSize.width), maxPan(containerSize.width))
        offsetY = (offsetY + panChange.y).coerceIn(-maxPan(containerSize.height), maxPan(containerSize.height))
    }
    val coilModel = when {
        asset.relativePath.startsWith("../assets/") -> asset.relativePath
        asset.filePath.startsWith("saf://") -> asset.filePath
        asset.filePath.startsWith("file://") || asset.filePath.startsWith("content://") -> asset.filePath
        else -> "file://${asset.filePath}"
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .onSizeChanged { containerSize = it }
                .viewerBackground(background)
                .transformable(transformableState)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { if (scale > 1f) resetZoom() else scale = DOUBLE_TAP_ZOOM },
                    )
                },
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
            if (scale > 1.01f) {
                IconButton(
                    onClick = { resetZoom() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset zoom",
                        tint = Color.White,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            ViewerBackground.entries.forEach { option ->
                FilterChip(
                    selected = background == option,
                    onClick = { background = option },
                    label = { Text(option.label) },
                )
            }
        }
    }
}

private fun Modifier.viewerBackground(mode: ViewerBackground): Modifier = when (mode) {
    ViewerBackground.THEME -> this
    ViewerBackground.WHITE -> this.background(Color.White)
    ViewerBackground.BLACK -> this.background(Color.Black)
    ViewerBackground.CHECKERBOARD -> this.background(Color.White).drawBehind { drawCheckerboard() }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCheckerboard(tilePx: Float = 24f) {
    val light = Color(0xFFE0E0E0)
    val cols = floor(size.width / tilePx).toInt() + 1
    val rows = floor(size.height / tilePx).toInt() + 1
    for (row in 0..rows) {
        for (col in 0..cols) {
            if ((row + col) % 2 == 0) {
                drawRect(
                    color = light,
                    topLeft = Offset(col * tilePx, row * tilePx),
                    size = androidx.compose.ui.geometry.Size(tilePx, tilePx),
                )
            }
        }
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
