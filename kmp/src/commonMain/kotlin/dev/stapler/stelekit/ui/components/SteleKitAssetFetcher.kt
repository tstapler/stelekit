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
 * Coil 3 [Mapper] that rewrites Logseq-style relative asset paths to loadable URIs.
 *
 * For file-system-backed graphs:
 *   Input:  "../assets/photo.jpg"
 *   Output: coil3.Uri("file:///absolute/graph/root/assets/photo.jpg")
 *
 * For SAF-backed graphs on Android, [fileSystem] overrides URI construction and returns a
 * `content://` document URI so Coil can load via ContentResolver.
 *
 * Strings that do not start with "../assets/" pass through as null so Coil falls through
 * to its default handling (http/https NetworkFetcher, absolute file:// FileUriFetcher, etc.).
 *
 * Returns null (cache miss) if [filename] contains path traversal sequences (`..`) or
 * path separator characters (`/`, `\`) to prevent escaping the assets directory.
 */
class SteleKitAssetMapper(graphRoot: String, private val fileSystem: FileSystem? = null) : Mapper<String, Uri> {
    private val graphRoot = graphRoot.trimEnd('/')

    override fun map(data: String, options: Options): Uri? {
        if (!data.startsWith("../assets/")) return null
        val filename = data.removePrefix("../assets/")
        // Guard against path traversal: reject backslashes and any ".." path component
        if (filename.contains('\\') || filename.split('/').any { it == ".." }) {
            return null
        }
        val platformUri = fileSystem?.buildAssetUri(graphRoot, "assets/$filename")
        return (platformUri ?: "file://$graphRoot/assets/$filename").toUri()
    }
}

/**
 * Coil 3 [Mapper] that converts a full `saf://` path stored in [ImageAnnotation.filePath]
 * to a `content://` document URI that Coil can load via ContentResolver on Android.
 *
 * Strings that do not start with `saf://` return null so Coil falls through to default handling.
 */
class SteleKitSafPathMapper(private val fileSystem: FileSystem) : Mapper<String, Uri> {
    override fun map(data: String, options: Options): Uri? {
        if (!data.startsWith("saf://")) return null
        return fileSystem.resolveLoadableUri(data)?.toUri()
    }
}

/**
 * Returns a Coil [ImageLoader] scoped to the current graph root path.
 * Registers [SteleKitAssetMapper] when [LocalGraphRootPath] is non-null so that
 * `../assets/<filename>` paths resolve to loadable URIs (file:// or content://).
 * Registers [SteleKitSafPathMapper] when [LocalFileSystem] is non-null so that
 * full `saf://` paths (used by [ImageAnnotation.filePath]) also resolve correctly.
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
                if (fileSystem != null) {
                    add(SteleKitSafPathMapper(fileSystem))
                }
            }
            .build()
    }
}
