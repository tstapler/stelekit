// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.image

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Creates and finds the thumbnail/display derivatives of an image. Never touches the original.
 *
 * - One decode at a time (a gallery of 100 cards must not decode 100 photos concurrently); the lock
 *   also makes concurrent requests for one image idempotent: the second finds the files.
 * - Both missing tiers come from a single sampled decode ([ImageDownscalerPort]).
 * - Any Throwable (including OOM) is swallowed and remembered for the session; callers fall back
 *   to the next tier, ultimately the original.
 * - Generation is lazy (import, or first view); nothing scans the graph.
 */
class ImageDerivativeService(
    private val downscaler: ImageDownscalerPort = PlatformImageDownscaler,
    private val dispatcher: CoroutineDispatcher = PlatformDispatcher.IO,
) {
    private val lock = Mutex()
    private val giveUp = HashSet<String>()

    /** What already exists (and is not older than the original). No decoding. */
    suspend fun existing(
        fs: FileSystem,
        graph: EffectiveNotesPath,
        original: String,
        recordedThumbnail: String? = null,
    ): ImageDerivativeSet = withContext(dispatcher) { lookup(fs, graph, original, recordedThumbnail) }

    suspend fun ensure(
        fs: FileSystem,
        graph: EffectiveNotesPath,
        original: String,
        recordedThumbnail: String? = null,
    ): ImageDerivativeSet = withContext(dispatcher) {
        lock.withLock {
            val found = lookup(fs, graph, original, recordedThumbnail)
            if ((found.thumbnail != null && found.display != null) || original in giveUp) {
                return@withLock found
            }
            try {
                generate(fs, graph, original, found)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                giveUp += original
                found
            }
        }
    }

    private fun lookup(fs: FileSystem, graph: EffectiveNotesPath, original: String, recorded: String?): ImageDerivativeSet {
        val originalMtime = runCatching { fs.getLastModifiedTime(original) }.getOrNull()
        fun fresh(path: String?): String? {
            if (path == null || !fs.fileExists(path)) return null
            val m = runCatching { fs.getLastModifiedTime(path) }.getOrNull()
            return path.takeIf { m == null || originalMtime == null || m >= originalMtime }
        }
        return ImageDerivativeSet(
            thumbnail = fresh(recorded) ?: fresh(ImageDerivativePaths.pathFor(graph, original, ImageTier.THUMBNAIL)),
            display = fresh(ImageDerivativePaths.pathFor(graph, original, ImageTier.DISPLAY)),
        )
    }

    private fun generate(
        fs: FileSystem,
        graph: EffectiveNotesPath,
        original: String,
        found: ImageDerivativeSet,
    ): ImageDerivativeSet {
        val wanted = buildList {
            if (found.display == null) add(ImageTier.DISPLAY)
            if (found.thumbnail == null) add(ImageTier.THUMBNAIL)
        }
        val source = fs.readFileBytes(original)
        val outputs = source?.let { downscaler.downscale(it, wanted.mapNotNull { t -> t.maxEdgePx }) }
        if (outputs == null) {
            giveUp += original
            return found
        }
        ensureCacheDir(fs, graph)
        var thumbnail = found.thumbnail
        var display = found.display
        wanted.forEachIndexed { idx, tier ->
            val bytes = outputs.getOrNull(idx)
            val path = ImageDerivativePaths.pathFor(graph, original, tier)
            val written = path != null && bytes != null && bytes.isNotEmpty() && fs.writeFileBytes(path, bytes)
            if (written) {
                if (tier == ImageTier.THUMBNAIL) thumbnail = path else display = path
            }
        }
        if (thumbnail == null && display == null) giveUp += original
        return ImageDerivativeSet(thumbnail, display)
    }

    private fun ensureCacheDir(fs: FileSystem, graph: EffectiveNotesPath) {
        for (sub in listOf(".stelekit", ".stelekit/cache", ".stelekit/cache/images")) {
            val dir = graph.child(sub)
            if (!fs.directoryExists(dir)) fs.createDirectory(dir)
        }
    }

    companion object {
        /** Process-wide instance so the single-decode lock spans every composable. */
        val shared: ImageDerivativeService by lazy { ImageDerivativeService() }
    }
}
