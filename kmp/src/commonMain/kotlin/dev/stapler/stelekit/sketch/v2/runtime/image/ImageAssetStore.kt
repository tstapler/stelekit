// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.image

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.sketch.v2.image.ImageFormat
import dev.stapler.stelekit.sketch.v2.image.ImageHeader
import dev.stapler.stelekit.sketch.v2.image.ImageHeaderProbe
import dev.stapler.stelekit.sketch.v2.image.ImageProbeError
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.util.ContentHasher

/** An image held in the graph assets folder. [fileId] is the lowercase SHA-256 hex of the bytes. */
data class StoredImage(val fileId: String, val header: ImageHeader, val path: String, val alreadyStored: Boolean)

sealed interface ImageStoreError {
    /** A short sentence for the user that states the cap where one applies. */
    val message: String

    data class TooLarge(val bytes: Long, val maxBytes: Long) : ImageStoreError {
        override val message: String get() = "Image too large (max ${maxBytes / BYTES_PER_MB} MB)"
    }

    data class TooManyPixels(val width: Int, val height: Int, val maxPixels: Int, val maxSidePx: Int) : ImageStoreError {
        override val message: String get() = "Image too large (max $maxPixels pixels, longest side $maxSidePx)"
    }

    data object Unsupported : ImageStoreError {
        override val message: String get() = "Unsupported image (use PNG, JPEG, GIF or WebP)"
    }

    data object Unreadable : ImageStoreError {
        override val message: String get() = "Image file is damaged"
    }

    data class WriteFailed(val path: String) : ImageStoreError {
        override val message: String get() = "Could not save the image"
    }

    private companion object {
        const val BYTES_PER_MB = 1_048_576L
    }
}

/**
 * Stores sketch images once each under the graph's `assets/` folder, named by the SHA-256 of their bytes, so an image
 * used twice, pasted twice or copied to another sheet costs one file and an element holds only the `fileId`
 * (Story 3.4.3). The header is probed before anything is written, so an oversized or hostile file never reaches a
 * decoder. [assetsDir] is the absolute path of the folder (no trailing slash needed).
 *
 * Files are written through [FileSystem.writeFileBytes]; the name is the hash, so a torn write is repaired by storing
 * the same bytes again (a size mismatch is treated as absent).
 */
class ImageAssetStore(
    private val fs: FileSystem,
    assetsDir: String,
    private val limits: SceneLimits = SceneLimits.Default,
) {
    private val dir = assetsDir.trimEnd('/')

    fun store(bytes: ByteArray): Either<ImageStoreError, StoredImage> {
        if (bytes.size.toLong() > limits.maxImageBytes) return ImageStoreError.TooLarge(bytes.size.toLong(), limits.maxImageBytes).left()
        val header = ImageHeaderProbe.probe(bytes).fold(
            { return when (it) {
                ImageProbeError.Unsupported -> ImageStoreError.Unsupported.left()
                ImageProbeError.Truncated, ImageProbeError.ZeroSize -> ImageStoreError.Unreadable.left()
            } },
            { it },
        )
        if (header.pixels > limits.maxImagePixels || header.width > limits.maxImageSidePx || header.height > limits.maxImageSidePx) {
            return ImageStoreError.TooManyPixels(header.width, header.height, limits.maxImagePixels, limits.maxImageSidePx).left()
        }
        val fileId = ContentHasher.sha256(bytes)
        val path = pathFor(fileId, header.format)
        if (holds(path, bytes.size)) return StoredImage(fileId, header, path, alreadyStored = true).right()
        if (!fs.directoryExists(dir)) fs.createDirectory(dir)
        if (!fs.writeFileBytes(path, bytes)) return ImageStoreError.WriteFailed(path).left()
        return StoredImage(fileId, header, path, alreadyStored = false).right()
    }

    /** The stored bytes for [fileId], or null when no such file exists. */
    fun read(fileId: String): ByteArray? = pathOf(fileId)?.let { fs.readFileBytes(it) }

    /** True when a file for [fileId] is present; drives the missing-file placeholder. */
    fun exists(fileId: String): Boolean = pathOf(fileId) != null

    private fun pathFor(fileId: String, format: ImageFormat) = "$dir/$fileId.${format.extension}"

    private fun pathOf(fileId: String): String? =
        if (!isFileId(fileId)) null else ImageFormat.entries.map { pathFor(fileId, it) }.firstOrNull { fs.fileExists(it) }

    private fun holds(path: String, size: Int): Boolean = fs.fileExists(path) && fs.readFileBytes(path)?.size == size

    private fun isFileId(id: String): Boolean = id.length == FILE_ID_LENGTH && id.all { it in '0'..'9' || it in 'a'..'f' }

    private companion object {
        /** SHA-256 hex digest length. */
        const val FILE_ID_LENGTH = 64
    }
}
