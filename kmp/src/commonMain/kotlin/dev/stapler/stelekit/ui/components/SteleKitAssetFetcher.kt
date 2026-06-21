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
import dev.stapler.stelekit.platform.FileSystem

/**
 * Coil 3 [Mapper] that rewrites Logseq-style relative asset paths to absolute URIs.
 *
 * Input:  "../assets/photo.jpg"
 * Output: coil3.Uri("file:///absolute/graph/root/assets/photo.jpg")
 *         or coil3.Uri("content://...") for SAF-backed graphs on Android
 *
 * Strings that do not start with "../assets/" pass through as null so Coil falls through
 * to its default handling (http/https NetworkFetcher, absolute file:// FileUriFetcher, etc.).
 *
 * Returns null (cache miss) if [filename] contains path traversal sequences (`..`) or
 * path separator characters (`/`, `\`) to prevent escaping the assets directory.
 */
class SteleKitAssetMapper(
    graphRoot: String,
    private val fileSystem: FileSystem? = null,
) : Mapper<String, Uri> {
    private val graphRoot = graphRoot.trimEnd('/')

    override fun map(data: String, options: Options): Uri? {
        if (!data.startsWith("../assets/")) return null
        val filename = data.removePrefix("../assets/")
        // Guard against path traversal: reject backslashes and any ".." path component
        if (filename.contains('\\') || filename.split('/').any { it == ".." }) {
            return null
        }
        // Delegate to platform FileSystem for SAF-backed graphs (returns content:// on Android)
        fileSystem?.resolveAssetUri(graphRoot, "assets/$filename")
            ?.let { return it.toUri() }
        return "file://$graphRoot/assets/$filename".toUri()
    }
}

/**
 * Returns a Coil [ImageLoader] scoped to the current graph root path.
 * Registers [SteleKitAssetMapper] when [LocalGraphRootPath] is non-null so that
 * `../assets/<filename>` paths resolve to absolute URIs (content:// for SAF graphs).
 */
@Composable
fun rememberSteleKitImageLoader(): ImageLoader {
    val graphRoot = LocalGraphRootPath.current
    val fileSystem = LocalFileSystem.current
    val context: PlatformContext = LocalPlatformContext.current
    return remember(graphRoot, fileSystem, context) {
        ImageLoader.Builder(context)
            .components {
                if (graphRoot != null) {
                    add(SteleKitAssetMapper(graphRoot, fileSystem))
                }
            }
            .build()
    }
}
