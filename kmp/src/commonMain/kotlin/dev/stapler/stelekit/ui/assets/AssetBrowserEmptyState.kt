package dev.stapler.stelekit.ui.assets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Empty-state composable displayed in [AssetBrowserScreen] when no assets match the current
 * [selectedFilter]. Mirrors the layout pattern from `GalleryEmptyState`.
 */
@Composable
fun AssetBrowserEmptyState(
    selectedFilter: AssetFilter,
    modifier: Modifier = Modifier,
) {
    val (icon, headline, body) = when (selectedFilter) {
        AssetFilter.ALL ->
            Triple(Icons.Default.FolderOpen, "No assets yet", "Attach a file to any page to see it here")
        AssetFilter.IMAGES ->
            Triple(Icons.Default.Image, "No images found", "Attach an image to any page")
        AssetFilter.PDFS ->
            Triple(Icons.Default.PictureAsPdf, "No PDFs found", "Attach a PDF to any page")
        AssetFilter.AUDIO ->
            Triple(Icons.Default.AudioFile, "No audio found", "Attach an audio file to any page")
        AssetFilter.VIDEO ->
            Triple(Icons.Default.VideoFile, "No videos found", "Attach a video to any page")
        AssetFilter.DOCUMENTS ->
            Triple(Icons.Default.Description, "No documents found", "Attach a document to any page")
        AssetFilter.FILES ->
            Triple(Icons.Default.InsertDriveFile, "No files found", "Attach a file to any page")
        AssetFilter.ORPHANED ->
            Triple(Icons.Default.LinkOff, "No orphaned assets", "All your assets are referenced by pages")
        is AssetFilter.TAG ->
            Triple(Icons.Default.Label, "No assets with tag \"${selectedFilter.name}\"", "Tag assets from the action menu")
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(headline, style = MaterialTheme.typography.headlineSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
