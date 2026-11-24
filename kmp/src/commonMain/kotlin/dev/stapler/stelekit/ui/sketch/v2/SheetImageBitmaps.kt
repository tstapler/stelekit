// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.platform.ImageBitmapDecoder
import dev.stapler.stelekit.platform.image.ExifOrientationReader
import dev.stapler.stelekit.platform.image.ImageDownscalerPort
import dev.stapler.stelekit.platform.image.PlatformImageDownscaler
import dev.stapler.stelekit.sketch.v2.image.ImageHeaderProbe
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The decoded images the canvas paints (epic E5): the photo underlay and any other image element. A photo straight off a phone is
 * 12 to 50 megapixels, so it is never decoded whole: [DISPLAY_EDGE_PX] caps the longer side of what is held (2048 px, about 16 MiB
 * as ARGB at most, a little over a phone's own screen width at fit and the same cap as the annotation loupe), reduced by
 * [PlatformImageDownscaler] before decoding so the full-size pixels are never in memory. Decoded bitmaps are kept up to
 * [budgetBytes] (the sheet's decoded-image budget), least recently drawn first out.
 *
 * [image] is read while drawing: a missing bitmap starts a load on the IO pool and the draw re-runs when it lands. It owns its
 * scope with a handler, so an out-of-memory error in a decode marks that file as failed instead of killing the process
 * (CLAUDE.md, uncaught coroutine Throwables on Android).
 */
class SheetImageBitmaps(
    private val bytesOf: (String) -> ByteArray?,
    private val budgetBytes: Long = SceneLimits.Default.maxDecodedImageBytesPerSheet,
    private val downscaler: ImageDownscalerPort = PlatformImageDownscaler,
    private val decoder: (ByteArray) -> ImageBitmap? = ImageBitmapDecoder()::decode,
    main: CoroutineDispatcher = PlatformDispatcher.Main,
    private val io: CoroutineDispatcher = PlatformDispatcher.IO,
    /** Told why a file could not be shown (an undecodable file, or an error such as running out of memory); the file then keeps its placeholder. */
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + main + CoroutineExceptionHandler { _, _ -> })
    private val ready = LinkedHashMap<String, ImageBitmap>()
    private val loading = HashSet<String>()
    private val failed = HashSet<String>()
    private var landed by mutableIntStateOf(0)

    /** The bitmap for [fileId], or null while it loads or when it cannot be shown (the painter then draws its placeholder). */
    fun image(fileId: String): ImageBitmap? {
        landed // read: a landing bitmap redraws the canvas
        val hit = ready.remove(fileId)
        if (hit != null) {
            ready[fileId] = hit
            return hit
        }
        if (fileId !in loading && fileId !in failed) start(fileId)
        return null
    }

    /** The pixel size of what is held for [fileId] (after the display cap), or null when nothing is held. */
    fun heldSize(fileId: String): Pair<Int, Int>? = ready[fileId]?.let { it.width to it.height }

    fun close() = scope.cancel()

    private fun start(fileId: String) {
        loading += fileId
        scope.launch {
            val bitmap = try {
                withContext(io) { decode(fileId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onFailure(e)
                null
            }
            loading -= fileId
            if (bitmap == null) failed += fileId else admit(fileId, bitmap)
            landed++
        }
    }

    private fun decode(fileId: String): ImageBitmap? = bytesOf(fileId)?.let { reduceAndDecode(it, downscaler, decoder) }

    private fun admit(fileId: String, bitmap: ImageBitmap) {
        ready[fileId] = bitmap
        var held = ready.values.sumOf { sizeOf(it) }
        val oldest = ready.keys.iterator()
        while (held > budgetBytes && oldest.hasNext()) {
            val id = oldest.next()
            if (id == fileId) break
            held -= sizeOf(ready.getValue(id))
            oldest.remove()
        }
    }

    private fun sizeOf(bitmap: ImageBitmap): Long = bitmap.width.toLong() * bitmap.height.toLong() * BYTES_PER_PIXEL

    companion object {
        /** Longer side, in pixels, of the largest bitmap held for display. */
        const val DISPLAY_EDGE_PX = 2048

        /** [source] reduced to at most [DISPLAY_EDGE_PX] on its longer side (EXIF orientation applied), then decoded; null when it cannot be. */
        fun reduceAndDecode(source: ByteArray, downscaler: ImageDownscalerPort = PlatformImageDownscaler, decoder: (ByteArray) -> ImageBitmap? = ImageBitmapDecoder()::decode): ImageBitmap? {
            val reduced = downscaler.downscale(source, listOf(DISPLAY_EDGE_PX))?.firstOrNull() ?: turnedCopy(source, downscaler) ?: source
            return decoder(reduced)
        }

        /**
         * A small photo is not reduced, but a platform decoder ignores its EXIF orientation: the photo is run through the downscaler one
         * pixel smaller so it comes back upright, which is the size the sheet recorded. A photo with no orientation is left byte for byte.
         */
        private fun turnedCopy(source: ByteArray, downscaler: ImageDownscalerPort): ByteArray? {
            if (ExifOrientationReader.read(source) !in TURNED_ORIENTATIONS) return null
            val header = ImageHeaderProbe.probe(source).getOrNull() ?: return null
            return downscaler.downscale(source, listOf(maxOf(header.width, header.height) - 1))?.firstOrNull()
        }

        private val TURNED_ORIENTATIONS = 2..8

        private const val BYTES_PER_PIXEL = 4L
    }
}
