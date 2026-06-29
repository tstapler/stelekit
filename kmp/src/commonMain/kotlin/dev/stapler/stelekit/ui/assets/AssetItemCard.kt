package dev.stapler.stelekit.ui.assets

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.ui.components.rememberSteleKitImageLoader

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssetItemCard(
    asset: AssetEntry,
    viewMode: ViewMode,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onAction: ((AssetAction) -> Unit)? = null,
) {
    when (viewMode) {
        ViewMode.GRID -> AssetGridItem(asset, modifier, onClick, onLongPress)
        ViewMode.LIST -> AssetListItem(asset, modifier, onClick, onLongPress, onAction)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssetGridItem(
    asset: AssetEntry,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .padding(4.dp)
            .size(120.dp)
            .combinedClickable(
                onClick = onClick ?: {},
                onLongClick = onLongPress,
            ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (asset.mediaType == AssetMediaType.IMAGE) {
                val imageLoader = rememberSteleKitImageLoader()
                AsyncImage(
                    model = asset.relativePath,
                    contentDescription = asset.filePath.substringAfterLast('/'),
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = assetIcon(asset.mediaType),
                    contentDescription = asset.filePath.substringAfterLast('/'),
                    modifier = Modifier.size(48.dp),
                )
            }
            if (asset.mlFailed) {
                Badge(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    containerColor = MaterialTheme.colorScheme.error,
                ) { Text("!") }
            } else if (asset.mlProcessed) {
                Badge(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                ) { Text("✓") }
            }
            Text(
                text = asset.filePath.substringAfterLast('/'),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                    .padding(2.dp)
                    .fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssetListItem(
    asset: AssetEntry,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onAction: ((AssetAction) -> Unit)? = null,
) {
    Box(
        modifier = modifier.combinedClickable(
            onClick = onClick ?: {},
            onLongClick = onLongPress,
        ),
    ) {
        ListItem(
            leadingContent = {
                if (asset.mediaType == AssetMediaType.IMAGE) {
                    val imageLoader = rememberSteleKitImageLoader()
                    AsyncImage(
                        model = asset.relativePath,
                        contentDescription = null,
                        imageLoader = imageLoader,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                } else {
                    Icon(
                        imageVector = assetIcon(asset.mediaType),
                        contentDescription = null,
                    )
                }
            },
            headlineContent = {
                Text(
                    text = asset.filePath.substringAfterLast('/'),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    text = "${asset.sizeBytes / 1024} KB · ${asset.mediaType.name}",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (asset.mlFailed) {
                        Icon(Icons.Default.Warning, contentDescription = "ML failed", tint = MaterialTheme.colorScheme.error)
                    } else if (asset.mlProcessed) {
                        Icon(Icons.Default.Check, contentDescription = "ML done", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            },
        )
    }
}

internal fun assetIcon(mediaType: AssetMediaType) = when (mediaType) {
    AssetMediaType.IMAGE -> Icons.Default.Image
    AssetMediaType.PDF -> Icons.Default.PictureAsPdf
    AssetMediaType.AUDIO -> Icons.Default.AudioFile
    AssetMediaType.VIDEO -> Icons.Default.VideoFile
    AssetMediaType.DOCUMENT -> Icons.Default.Description
    AssetMediaType.FILE -> Icons.Default.InsertDriveFile
}

sealed interface AssetAction {
    data object Open : AssetAction
    data object CopyLink : AssetAction
    data class Rename(val newName: String) : AssetAction
    data class MoveToFolder(val subfolder: String) : AssetAction
    data object Delete : AssetAction
    data class EditTags(val tags: List<String>) : AssetAction
}
