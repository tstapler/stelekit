// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext

// TODO: SteleKitAssetFetcher - requires platform FileSystem (java.io.File is JVM-only).
// Implement per-platform in jvmMain/androidMain/iosMain to resolve Logseq-style relative
// asset paths (e.g. `../assets/image.png`) against the graph root directory.
// When implemented, register it in rememberSteleKitImageLoader via .components { add(Factory()) }.

/**
 * Returns a Coil [ImageLoader] scoped to the current graph root path.
 * When [LocalGraphRootPath] is non-null a custom [SteleKitAssetFetcher] (per-platform)
 * will be registered to resolve relative `../assets/` paths from the graph directory.
 */
@Composable
fun rememberSteleKitImageLoader(): ImageLoader {
    val graphRoot = LocalGraphRootPath.current
    val context: PlatformContext = LocalPlatformContext.current
    return remember(graphRoot, context) {
        ImageLoader.Builder(context)
            // TODO: add(SteleKitAssetFetcher.Factory(graphRoot)) once per-platform fetcher is implemented
            .build()
    }
}
