package dev.stapler.stelekit.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.stapler.stelekit.platform.image.ImageTier
import dev.stapler.stelekit.ui.assets.assetImageReference
import dev.stapler.stelekit.ui.assets.coilModelFor
import dev.stapler.stelekit.ui.components.rememberSteleKitImageLoader
import dev.stapler.stelekit.ui.components.rememberTieredImageModel
import dev.stapler.stelekit.ui.i18n.I18n
import dev.stapler.stelekit.ui.i18n.t
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Unique, spoken description of a card (distinct per item: includes date and measurement count / size). */
object LibraryCardDescription {
    fun of(item: LibraryItem): String {
        val date = Instant.fromEpochMilliseconds(item.lastAnnotatedAtMs ?: item.importedAtMs)
            .toLocalDateTime(TimeZone.UTC).date.toString()
        return if (item.annotated) {
            val n = item.measurementCount
            "${I18n.get(I18n.LIBRARY_BADGE_ANNOTATED)} photo, $date, $n ${if (n == 1) "measurement" else "measurements"}, ${item.fileName}"
        } else {
            "${typeWord(item)} file, ${item.fileName}, ${formatSize(item.sizeBytes)}"
        }
    }

    private fun typeWord(item: LibraryItem) = when (item.mediaClass) {
        LibraryMediaClass.IMAGES -> "Image"
        LibraryMediaClass.PDFS -> "PDF"
        LibraryMediaClass.AUDIO -> "Audio"
        LibraryMediaClass.VIDEO -> "Video"
        LibraryMediaClass.DOCS -> "Document"
        LibraryMediaClass.OTHER -> "Other"
    }

    fun formatSize(bytes: Long): String = when {
        bytes < KB -> "$bytes B"
        bytes < MB -> "${bytes / KB} KB"
        else -> "${bytes / MB} MB"
    }

    private const val KB = 1024L
    private const val MB = 1024L * 1024L
}

/**
 * One Library card. Tapping an annotated image opens the editor; any other card opens the detail
 * (plan decision 7). The badge is icon plus word so annotation state is not colour-only.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryItemCard(
    item: LibraryItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = LibraryCardDescription.of(item)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .testTag("library-card-${item.id}")
            .semantics { contentDescription = description; role = Role.Button }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.fillMaxSize().clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            if (item.isImage) ImageThumb(item) else Icon(typeIcon(item.mediaClass), contentDescription = null, modifier = Modifier.size(TYPE_ICON_DP.dp))
            if (item.annotated) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("library-badge-annotated"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Straighten, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(
                        "${t(I18n.LIBRARY_BADGE_ANNOTATED)} · ${item.measurementCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Text(
                text = item.fileName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = NAME_SCRIM_ALPHA))
                    .padding(2.dp),
            )
        }
    }
}

@Composable
private fun ImageThumb(item: LibraryItem) {
    val imageLoader = rememberSteleKitImageLoader()
    val reference = item.thumbnailPath ?: assetImageReference(item.relativePath, item.filePath)
    val tiered by rememberTieredImageModel(reference, ImageTier.THUMBNAIL)
    AsyncImage(
        model = tiered.model?.let(::coilModelFor),
        contentDescription = null,
        imageLoader = imageLoader,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

private fun typeIcon(c: LibraryMediaClass) = when (c) {
    LibraryMediaClass.PDFS -> Icons.Default.PictureAsPdf
    LibraryMediaClass.AUDIO -> Icons.Default.AudioFile
    LibraryMediaClass.VIDEO -> Icons.Default.VideoFile
    LibraryMediaClass.DOCS -> Icons.Default.Description
    else -> Icons.Default.InsertDriveFile
}

private const val TYPE_ICON_DP = 48
private const val NAME_SCRIM_ALPHA = 0.8f
