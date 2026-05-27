// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage

/**
 * Renders a Logseq image node as a full-width async image using Coil 3.
 *
 * Supports:
 * - Absolute http/https URLs (loaded via Coil's default network fetcher)
 * - Relative Logseq asset paths such as `../assets/image.png` (resolved via
 *   [rememberSteleKitImageLoader] once the per-platform [SteleKitAssetFetcher] is wired in)
 *
 * Tapping the image opens a fullscreen lightbox. The lightbox provides a close
 * button and an edit button that triggers [onStartEditing].
 */
@Composable
internal fun ImageBlock(
    url: String,
    altText: String,
    onStartEditing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val imageLoader = rememberSteleKitImageLoader()
    var showLightbox by remember { mutableStateOf(false) }

    AsyncImage(
        model = url,
        contentDescription = altText,
        imageLoader = imageLoader,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxWidth()
            .clickable { showLightbox = true }
    )

    if (showLightbox) {
        ImageLightbox(
            url = url,
            altText = altText,
            onDismiss = { showLightbox = false },
            onEdit = {
                showLightbox = false
                onStartEditing()
            }
        )
    }
}

@Composable
private fun ImageLightbox(
    url: String,
    altText: String,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    val imageLoader = rememberSteleKitImageLoader()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable { onDismiss() }
        ) {
            AsyncImage(
                model = url,
                contentDescription = altText,
                imageLoader = imageLoader,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Edit block", tint = Color.White)
            }
        }
    }
}
