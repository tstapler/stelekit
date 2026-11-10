// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import arrow.atomic.AtomicInt
import coil3.EventListener
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.Uri
import coil3.compose.LocalPlatformContext
import coil3.map.Mapper
import coil3.memory.MemoryCache
import coil3.request.Options
import coil3.toUri
import dev.stapler.stelekit.model.EffectiveNotesPath
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
    private val notesPath: EffectiveNotesPath,
    private val fileSystem: FileSystem? = null,
) : Mapper<String, Uri> {
    override fun map(data: String, options: Options): Uri? {
        if (!data.startsWith("../assets/")) return null
        val filename = data.removePrefix("../assets/")
        // Guard against path traversal: reject backslashes, ".." components, and
        // percent-encoded dots (%2e / %2E) which bypass the literal ".." check.
        val lowerFilename = filename.lowercase()
        if (filename.contains('\\') ||
            lowerFilename.contains("%2e") ||
            filename.split('/').any { it == ".." }
        ) {
            return null
        }
        // Delegate to platform FileSystem for SAF-backed graphs (returns content:// on Android)
        fileSystem?.resolveAssetUri(notesPath, "assets/$filename")
            ?.let { return it.toUri() }
        return "file://${notesPath.child("assets/$filename")}".toUri()
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

/** Share of the app heap budget the single image memory cache may use. */
private const val IMAGE_MEMORY_CACHE_PERCENT = 0.10

/**
 * One bounded memory cache for every [ImageLoader] this app builds. Each loader otherwise gets its
 * own cache sized at Coil's default share of the heap, and Coil keeps every built loader reachable
 * (it registers a ComponentCallbacks with the Context), so one loader per image block/asset card
 * multiplied the cache budget instead of bounding it. Touched from composition only (main thread).
 */
private var sharedMemoryCache: MemoryCache? = null

internal fun sharedImageMemoryCache(context: PlatformContext): MemoryCache =
    sharedMemoryCache ?: MemoryCache.Builder()
        .maxSizePercent(context, IMAGE_MEMORY_CACHE_PERCENT)
        .build()
        .also { sharedMemoryCache = it }

/** Bytes held by the shared image memory cache, or null before any loader was built. Diagnostics only. */
fun sharedImageMemoryCacheSizeBytes(): Long? = sharedMemoryCache?.size?.toLong()

/** Empties and recreates the shared cache so a benchmark scenario starts cold. Test seam only. */
internal fun resetSharedImageMemoryCacheForTest() {
    sharedMemoryCache?.clear()
    sharedMemoryCache = null
}

/** How many loaders [buildSteleKitImageLoader] has built; read-only for the benchmark probe. */
private val loaderConstructionCount = AtomicInt(0)

internal fun imageLoaderConstructions(): Int = loaderConstructionCount.get()

/** Zeroes the construction counter between benchmark scenarios. Test seam only. */
internal fun resetImageLoaderConstructionsForTest() = loaderConstructionCount.set(0)

/** [eventListenerFactory] stays null in production; the benchmark probe passes a decode counter. */
internal fun buildSteleKitImageLoader(
    context: PlatformContext,
    notesPath: EffectiveNotesPath?,
    fileSystem: FileSystem?,
    eventListenerFactory: EventListener.Factory? = null,
): ImageLoader {
    loaderConstructionCount.incrementAndGet()
    return ImageLoader.Builder(context)
        .memoryCache(sharedImageMemoryCache(context))
        .apply { if (eventListenerFactory != null) this.eventListenerFactory(eventListenerFactory) }
        .components {
            if (notesPath != null) {
                add(SteleKitAssetMapper(notesPath, fileSystem))
            }
            if (fileSystem != null) {
                add(SteleKitSafPathMapper(fileSystem))
            }
        }
        .build()
}

/**
 * Returns a Coil [ImageLoader] scoped to the current notes folder.
 * Registers [SteleKitAssetMapper] when [LocalNotesPath] is non-null so that
 * `../assets/<filename>` paths resolve to absolute URIs (content:// for SAF graphs).
 * Registers [SteleKitSafPathMapper] when [LocalFileSystem] is non-null so that
 * full `saf://` paths (used by [ImageAnnotation.filePath]) also resolve correctly.
 * All loaders share one bounded memory cache ([sharedImageMemoryCache]).
 */
@Composable
fun rememberSteleKitImageLoader(): ImageLoader {
    val notesPath = LocalNotesPath.current
    val fileSystem = LocalFileSystem.current
    val context: PlatformContext = LocalPlatformContext.current
    return remember(notesPath, fileSystem, context) {
        buildSteleKitImageLoader(context, notesPath, fileSystem)
    }
}
