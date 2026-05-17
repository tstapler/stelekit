// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.Uri
import coil3.compose.LocalPlatformContext
import coil3.map.Mapper
import coil3.request.Options
import coil3.toUri

/**
 * Coil 3 [Mapper] that rewrites Logseq-style relative asset paths to absolute file:// URIs.
 *
 * Input:  "../assets/photo.jpg"
 * Output: coil3.Uri("file:///absolute/graph/root/assets/photo.jpg")
 *
 * Strings that do not start with "../assets/" pass through as null so Coil falls through
 * to its default handling (http/https NetworkFetcher, absolute file:// FileUriFetcher, etc.).
 *
 * Returns null (cache miss) if [filename] contains path traversal sequences (`..`) or
 * path separator characters (`/`, `\`) to prevent escaping the assets directory.
 */
class SteleKitAssetMapper(private val graphRoot: String) : Mapper<String, Uri> {
    override fun map(data: String, options: Options): Uri? {
        if (!data.startsWith("../assets/")) return null
        val filename = data.removePrefix("../assets/")
        // Guard against path traversal: reject backslashes and any ".." path component
        if (filename.contains('\\') || filename.split('/').any { it == ".." }) {
            return null
        }
        return "file://$graphRoot/assets/$filename".toUri()
    }
}

/**
 * Returns a Coil [ImageLoader] scoped to the current graph root path.
 * Registers [SteleKitAssetMapper] when [LocalGraphRootPath] is non-null so that
 * `../assets/<filename>` paths resolve to absolute file:// URIs.
 */
@Composable
fun rememberSteleKitImageLoader(): ImageLoader {
    val graphRoot = LocalGraphRootPath.current
    val context: PlatformContext = LocalPlatformContext.current
    return remember(graphRoot, context) {
        ImageLoader.Builder(context)
            .components {
                if (graphRoot != null) {
                    add(SteleKitAssetMapper(graphRoot))
                }
            }
            .build()
    }
}
