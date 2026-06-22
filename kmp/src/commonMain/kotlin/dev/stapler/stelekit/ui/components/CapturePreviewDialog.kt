// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext

/**
 * Full-screen dialog that previews a just-captured photo before it is imported.
 *
 * [imagePath] is the absolute file path returned by the camera provider (always a
 * cache-dir path on Android — never a content:// URI). Coil's FileUriFetcher loads
 * it directly from the "file://" scheme without any custom mapper.
 *
 * The user taps [Save] to confirm import or [Discard] to throw the capture away.
 */
@Composable
internal fun CapturePreviewDialog(
    imagePath: String,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val imageLoader = remember(context) { ImageLoader.Builder(context).build() }

    Dialog(
        onDismissRequest = onDiscard,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AsyncImage(
                model = "file://$imagePath",
                contentDescription = "Captured photo preview",
                imageLoader = imageLoader,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 500.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onDiscard) { Text("Discard") }
                Button(onClick = onSave) { Text("Save") }
            }
        }
    }
}
