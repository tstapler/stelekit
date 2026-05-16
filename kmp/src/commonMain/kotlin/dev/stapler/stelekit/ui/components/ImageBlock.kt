// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

// TODO: Wire ImageBlock in BlockItem dispatch when paragraph block contains only an ImageNode
// For now, ImageNode in mixed content renders as a link annotation via MarkdownEngine.

/**
 * Renders a Logseq image node as a full-width async image using Coil 3.
 *
 * Supports:
 * - Absolute http/https URLs (loaded via Coil's default network fetcher)
 * - Relative Logseq asset paths such as `../assets/image.png` (resolved via
 *   [rememberSteleKitImageLoader] once the per-platform [SteleKitAssetFetcher] is wired in)
 *
 * Tapping the image triggers [onStartEditing] so the user can edit the underlying block.
 */
@Composable
internal fun ImageBlock(
    url: String,
    altText: String,
    onStartEditing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val imageLoader = rememberSteleKitImageLoader()
    AsyncImage(
        model = url,
        contentDescription = altText,
        imageLoader = imageLoader,
        contentScale = ContentScale.FillWidth,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp)
            .clickable { onStartEditing() }
    )
}
