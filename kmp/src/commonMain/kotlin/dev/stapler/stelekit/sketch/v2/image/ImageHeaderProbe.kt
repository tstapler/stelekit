// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.image

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/** The image formats a sheet may reference. SVG is never allowed (it can carry script). */
enum class ImageFormat(val mime: String, val extension: String) {
    Png("image/png", "png"),
    Jpeg("image/jpeg", "jpg"),
    Gif("image/gif", "gif"),
    Webp("image/webp", "webp"),
}

data class ImageHeader(val format: ImageFormat, val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height.toLong()
}

sealed interface ImageProbeError {
    /** Not PNG, JPEG, GIF or WebP. */
    data object Unsupported : ImageProbeError

    /** A recognised format whose header ends before the dimensions. */
    data object Truncated : ImageProbeError

    /** A header that states a zero, negative (PNG side of 2^31 or more) or otherwise unusable width or height. */
    data object ZeroSize : ImageProbeError
}

/**
 * Reads the pixel size from the header bytes only, so a bitmap is never allocated for an oversized or hostile image
 * (Story 3.4.3). Pure; works on the first few hundred bytes for PNG, GIF and WebP and scans markers for JPEG.
 */
object ImageHeaderProbe {
    fun probe(bytes: ByteArray): Either<ImageProbeError, ImageHeader> {
        val header = try {
            when {
                isPng(bytes) -> png(bytes)
                isJpeg(bytes) -> jpeg(bytes)
                isGif(bytes) -> gif(bytes)
                isWebp(bytes) -> webp(bytes)
                else -> return ImageProbeError.Unsupported.left()
            }
        } catch (_: IndexOutOfBoundsException) {
            null
        } ?: return ImageProbeError.Truncated.left()
        // `be32` is signed: a PNG side of 0x80000000 or more arrives negative and is as unusable as zero.
        return if (header.width < 1 || header.height < 1) ImageProbeError.ZeroSize.left() else header.right()
    }

    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val PNG_HEADER_BYTES = 24
    private const val PNG_WIDTH_AT = 16
    private const val PNG_HEIGHT_AT = 20
    private const val GIF_HEADER_BYTES = 10
    private const val GIF_WIDTH_AT = 6
    private const val GIF_HEIGHT_AT = 8
    private const val RIFF_HEADER_BYTES = 12
    private const val WEBP_CHUNK_HEADER = 8
    private const val WEBP_CHUNK_AT = 12
    private const val WEBP_DATA_AT = WEBP_CHUNK_AT + WEBP_CHUNK_HEADER
    private const val VP8_SYNC_AT = 3
    private const val VP8_WIDTH_AT = 6
    private const val VP8_HEIGHT_AT = 8
    private const val VP8_DIMENSION_MASK = 0x3FFF
    private const val VP8L_SIGNATURE = 0x2F
    private const val VP8L_BITS_AT = 1
    private const val VP8L_FIELD_MASK = 0x3FFF
    private const val VP8L_HEIGHT_SHIFT = 14
    private const val VP8X_WIDTH_AT = 4
    private const val VP8X_HEIGHT_AT = 7
    private const val JPEG_MARKER_PREFIX = 0xFF
    private const val JPEG_SOI_BYTES = 2
    private const val JPEG_SEGMENT_HEADER = 4
    private const val JPEG_SOF_HEIGHT_AT = 5
    private const val JPEG_SOF_WIDTH_AT = 7
    private const val JPEG_SOS = 0xDA
    private const val JPEG_DHT = 0xC4
    private const val JPEG_JPG = 0xC8
    private const val JPEG_DAC = 0xCC
    private val jpegSof = 0xC0..0xCF
    private val jpegStandalone = 0xD0..0xD9

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private fun be16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)

    private fun be32(b: ByteArray, i: Int): Int = (be16(b, i) shl 16) or be16(b, i + 2)

    private fun le16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)

    private fun le24(b: ByteArray, i: Int): Int = le16(b, i) or (u8(b, i + 2) shl 16)

    private fun isPng(b: ByteArray) = b.size >= pngSignature.size && pngSignature.indices.all { b[it] == pngSignature[it] }

    private fun isJpeg(b: ByteArray) = b.size >= JPEG_SOI_BYTES && u8(b, 0) == JPEG_MARKER_PREFIX && u8(b, 1) == 0xD8

    private fun isGif(b: ByteArray) = b.size >= GIF_WIDTH_AT && b.decodeToString(0, GIF_WIDTH_AT) in setOf("GIF87a", "GIF89a")

    private fun isWebp(b: ByteArray) = b.size >= RIFF_HEADER_BYTES && b.decodeToString(0, 4) == "RIFF" && b.decodeToString(8, 12) == "WEBP"

    private fun png(b: ByteArray): ImageHeader? =
        if (b.size < PNG_HEADER_BYTES) null else ImageHeader(ImageFormat.Png, be32(b, PNG_WIDTH_AT), be32(b, PNG_HEIGHT_AT))

    private fun gif(b: ByteArray): ImageHeader? =
        if (b.size < GIF_HEADER_BYTES) null else ImageHeader(ImageFormat.Gif, le16(b, GIF_WIDTH_AT), le16(b, GIF_HEIGHT_AT))

    private fun jpeg(b: ByteArray): ImageHeader? {
        var i = JPEG_SOI_BYTES
        while (i + JPEG_SEGMENT_HEADER <= b.size) {
            if (u8(b, i) != JPEG_MARKER_PREFIX) return null
            var marker = u8(b, i + 1)
            while (marker == JPEG_MARKER_PREFIX && i + JPEG_SEGMENT_HEADER < b.size) {
                i++
                marker = u8(b, i + 1)
            }
            if (marker in jpegStandalone || marker == 0x01) {
                i += JPEG_SOI_BYTES
                continue
            }
            if (isSofMarker(marker)) {
                if (i + JPEG_SOF_WIDTH_AT + JPEG_SOI_BYTES + 1 > b.size) return null
                return ImageHeader(ImageFormat.Jpeg, be16(b, i + JPEG_SOF_WIDTH_AT), be16(b, i + JPEG_SOF_HEIGHT_AT))
            }
            if (marker == JPEG_SOS) return null
            i += JPEG_SOI_BYTES + be16(b, i + JPEG_SOI_BYTES)
        }
        return null
    }

    /** SOF0..SOF15 except the three codes in that range that are not frame headers (DHT, JPG, DAC). */
    private fun isSofMarker(marker: Int) = marker in jpegSof && marker != JPEG_DHT && marker != JPEG_JPG && marker != JPEG_DAC

    private fun webp(b: ByteArray): ImageHeader? {
        if (b.size < WEBP_DATA_AT) return null
        return when (b.decodeToString(WEBP_CHUNK_AT, WEBP_CHUNK_AT + 4)) {
            "VP8 " -> if (b.size < WEBP_DATA_AT + VP8_HEIGHT_AT + 2) null else {
                ImageHeader(
                    ImageFormat.Webp,
                    le16(b, WEBP_DATA_AT + VP8_WIDTH_AT) and VP8_DIMENSION_MASK,
                    le16(b, WEBP_DATA_AT + VP8_HEIGHT_AT) and VP8_DIMENSION_MASK,
                ).takeIf { u8(b, WEBP_DATA_AT + VP8_SYNC_AT) == 0x9D }
            }
            "VP8L" -> if (b.size < WEBP_DATA_AT + VP8L_BITS_AT + 4 || u8(b, WEBP_DATA_AT) != VP8L_SIGNATURE) null else {
                val bits = le16(b, WEBP_DATA_AT + VP8L_BITS_AT) or (le16(b, WEBP_DATA_AT + VP8L_BITS_AT + 2) shl 16)
                ImageHeader(ImageFormat.Webp, (bits and VP8L_FIELD_MASK) + 1, ((bits ushr VP8L_HEIGHT_SHIFT) and VP8L_FIELD_MASK) + 1)
            }
            "VP8X" -> if (b.size < WEBP_DATA_AT + VP8X_HEIGHT_AT + 3) null else {
                ImageHeader(ImageFormat.Webp, le24(b, WEBP_DATA_AT + VP8X_WIDTH_AT) + 1, le24(b, WEBP_DATA_AT + VP8X_HEIGHT_AT) + 1)
            }
            else -> null
        }
    }
}
