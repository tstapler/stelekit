// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

/** The image kinds the importer admits (D-5): SVG is refused because it needs sanitising or rasterising. */
enum class ImageKind(val mime: String) {
    Png("image/png"),
    Jpeg("image/jpeg"),
    Gif("image/gif"),
    Webp("image/webp"),
}

/** What the header of an image says before any pixel is decoded. */
sealed interface HeaderProbe {
    data class Found(val kind: ImageKind, val widthPx: Int, val heightPx: Int) : HeaderProbe

    /** The bytes so far look like [kind] but the dimensions are further in (JPEG with a large EXIF block). */
    data class NeedMore(val kind: ImageKind) : HeaderProbe

    data object Unknown : HeaderProbe
}

/**
 * Reads image dimensions from the file header (PNG IHDR, GIF logical screen, WebP VP8/VP8L/VP8X, JPEG SOF), so a
 * decompression bomb (a few KB that declare 60,000 x 60,000 pixels) is refused before any pixel decode.
 */
object ImageHeader {
    fun probe(b: ByteArray): HeaderProbe = when {
        isPng(b) -> if (b.size < PNG_HEADER_END) HeaderProbe.NeedMore(ImageKind.Png) else
            HeaderProbe.Found(ImageKind.Png, be32(b, PNG_WIDTH_AT), be32(b, PNG_HEIGHT_AT))
        isGif(b) -> if (b.size < GIF_HEADER_END) HeaderProbe.NeedMore(ImageKind.Gif) else
            HeaderProbe.Found(ImageKind.Gif, le16(b, GIF_WIDTH_AT), le16(b, GIF_HEIGHT_AT))
        isWebp(b) -> probeWebp(b)
        isJpeg(b) -> probeJpeg(b)
        looksLikePrefix(b) -> HeaderProbe.NeedMore(ImageKind.Png)
        else -> HeaderProbe.Unknown
    }

    private fun isPng(b: ByteArray) = startsWith(b, PNG_SIGNATURE)
    private fun isGif(b: ByteArray) = startsWith(b, GIF87) || startsWith(b, GIF89)
    private fun isJpeg(b: ByteArray) = b.size >= JPEG_SIGNATURE_BYTES && u8(b, 0) == MARKER_PREFIX && u8(b, 1) == JPEG_SOI
    private fun isWebp(b: ByteArray) = b.size >= WEBP_FOURCC_END && startsWith(b, RIFF) && b.sliceArray(WEBP_TAG_AT until WEBP_FOURCC_END).decodeToString() == WEBP_TAG

    /** Fewer bytes than any signature: ask for more rather than call it unknown. */
    private fun looksLikePrefix(b: ByteArray) = b.size < WEBP_FOURCC_END && listOf(PNG_SIGNATURE, GIF87, GIF89, RIFF, byteArrayOf(MARKER_PREFIX.toByte())).any {
        b.indices.all { i -> i >= it.size || b[i] == it[i] }
    }

    private fun probeWebp(b: ByteArray): HeaderProbe {
        if (b.size < WEBP_CHUNK_DATA_AT) return HeaderProbe.NeedMore(ImageKind.Webp)
        return when (b.sliceArray(WEBP_CHUNK_AT until WEBP_CHUNK_DATA_AT).decodeToString()) {
            "VP8 " -> if (b.size < VP8_END) HeaderProbe.NeedMore(ImageKind.Webp) else
                HeaderProbe.Found(ImageKind.Webp, le16(b, VP8_WIDTH_AT) and VP8_DIM_MASK, le16(b, VP8_HEIGHT_AT) and VP8_DIM_MASK)
            "VP8L" -> if (b.size < VP8L_END) HeaderProbe.NeedMore(ImageKind.Webp) else {
                val bits = le32(b, VP8L_BITS_AT)
                HeaderProbe.Found(ImageKind.Webp, (bits and VP8L_DIM_MASK) + 1, ((bits ushr VP8L_DIM_BITS) and VP8L_DIM_MASK) + 1)
            }
            "VP8X" -> if (b.size < VP8X_END) HeaderProbe.NeedMore(ImageKind.Webp) else
                HeaderProbe.Found(ImageKind.Webp, le24(b, VP8X_WIDTH_AT) + 1, le24(b, VP8X_HEIGHT_AT) + 1)
            else -> HeaderProbe.Unknown
        }
    }

    private fun probeJpeg(b: ByteArray): HeaderProbe {
        var i = JPEG_SIGNATURE_BYTES
        while (true) {
            if (i + MARKER_BYTES > b.size) return HeaderProbe.NeedMore(ImageKind.Jpeg)
            if (u8(b, i) != MARKER_PREFIX) return HeaderProbe.Unknown
            val marker = u8(b, i + 1)
            when {
                marker == MARKER_PREFIX -> i++ // fill byte
                marker == JPEG_TEM || marker in JPEG_RST_FIRST..JPEG_RST_LAST -> i += MARKER_BYTES
                marker == JPEG_EOI || marker == JPEG_SOS -> return HeaderProbe.Unknown
                else -> {
                    if (i + MARKER_BYTES + SEGMENT_LENGTH_BYTES > b.size) return HeaderProbe.NeedMore(ImageKind.Jpeg)
                    val segmentLength = be16(b, i + MARKER_BYTES)
                    if (isStartOfFrame(marker)) {
                        if (i + SOF_END > b.size) return HeaderProbe.NeedMore(ImageKind.Jpeg)
                        return HeaderProbe.Found(ImageKind.Jpeg, be16(b, i + SOF_WIDTH_AT), be16(b, i + SOF_HEIGHT_AT))
                    }
                    if (segmentLength < SEGMENT_LENGTH_BYTES) return HeaderProbe.Unknown
                    i += MARKER_BYTES + segmentLength
                }
            }
        }
    }

    private fun isStartOfFrame(marker: Int) =
        marker in JPEG_SOF_FIRST..JPEG_SOF_LAST && marker != JPEG_DHT && marker != JPEG_JPG && marker != JPEG_DAC

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and BYTE_MASK
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl BYTE_BITS) or u8(b, i + 1)
    private fun be32(b: ByteArray, i: Int) = (be16(b, i) shl SHORT_BITS) or be16(b, i + SHORT_BYTES)
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl BYTE_BITS)
    private fun le24(b: ByteArray, i: Int) = le16(b, i) or (u8(b, i + SHORT_BYTES) shl SHORT_BITS)
    private fun le32(b: ByteArray, i: Int) = le24(b, i) or (u8(b, i + SHORT_BYTES + 1) shl LE24_BITS)

    private fun startsWith(b: ByteArray, prefix: ByteArray) = b.size >= prefix.size && prefix.indices.all { b[it] == prefix[it] }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val GIF87 = "GIF87a".encodeToByteArray()
    private val GIF89 = "GIF89a".encodeToByteArray()
    private val RIFF = "RIFF".encodeToByteArray()
    private const val WEBP_TAG = "WEBP"

    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8
    private const val SHORT_BITS = 16
    private const val LE24_BITS = 24
    private const val SHORT_BYTES = 2

    private const val PNG_WIDTH_AT = 16
    private const val PNG_HEIGHT_AT = 20
    private const val PNG_HEADER_END = 24
    private const val GIF_WIDTH_AT = 6
    private const val GIF_HEIGHT_AT = 8
    private const val GIF_HEADER_END = 10

    private const val WEBP_TAG_AT = 8
    private const val WEBP_FOURCC_END = 12
    private const val WEBP_CHUNK_AT = 12
    private const val WEBP_CHUNK_DATA_AT = 16
    private const val VP8_WIDTH_AT = 26
    private const val VP8_HEIGHT_AT = 28
    private const val VP8_END = 30
    private const val VP8_DIM_MASK = 0x3FFF
    private const val VP8L_BITS_AT = 21
    private const val VP8L_END = 25
    private const val VP8L_DIM_BITS = 14
    private const val VP8L_DIM_MASK = 0x3FFF
    private const val VP8X_WIDTH_AT = 24
    private const val VP8X_HEIGHT_AT = 27
    private const val VP8X_END = 30

    private const val MARKER_PREFIX = 0xFF
    private const val MARKER_BYTES = 2
    private const val SEGMENT_LENGTH_BYTES = 2
    private const val JPEG_SIGNATURE_BYTES = 2
    private const val JPEG_SOI = 0xD8
    private const val JPEG_EOI = 0xD9
    private const val JPEG_SOS = 0xDA
    private const val JPEG_TEM = 0x01
    private const val JPEG_RST_FIRST = 0xD0
    private const val JPEG_RST_LAST = 0xD7
    private const val JPEG_SOF_FIRST = 0xC0
    private const val JPEG_SOF_LAST = 0xCF
    private const val JPEG_DHT = 0xC4
    private const val JPEG_JPG = 0xC8
    private const val JPEG_DAC = 0xCC
    private const val SOF_HEIGHT_AT = 5
    private const val SOF_WIDTH_AT = 7
    private const val SOF_END = 9
}
