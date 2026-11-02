package dev.stapler.stelekit.asset.pipeline

/**
 * Removes embedded metadata (EXIF/GPS, XMP, IPTC, comments) from JPEG and PNG bytes without
 * decoding pixels, so the image the cloud plugin uploads carries no location or camera details.
 * Other formats are returned unchanged; a malformed stream is returned as far as it parsed.
 */
object ExifStripper {
    private const val JPEG_MARKER_PREFIX = 0xFF
    private const val SOI = 0xD8
    private const val SOS = 0xDA
    private const val EOI = 0xD9
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val PNG_METADATA_CHUNKS = setOf("eXIf", "tEXt", "iTXt", "zTXt")

    fun strip(bytes: ByteArray): ByteArray = when {
        bytes.size >= 2 && bytes[0].u() == JPEG_MARKER_PREFIX && bytes[1].u() == SOI -> stripJpeg(bytes)
        bytes.size >= 8 && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] } -> stripPng(bytes)
        else -> bytes
    }

    private fun Byte.u(): Int = toInt() and 0xFF

    // APP1 (EXIF, XMP), APP3-APP15 (including IPTC in APP13) and COM go; APP0 (JFIF) and APP2 (ICC colour) stay.
    private fun isMetadataMarker(marker: Int): Boolean =
        marker == 0xE1 || marker in 0xE3..0xEF || marker == 0xFE

    private fun stripJpeg(src: ByteArray): ByteArray {
        val keep = mutableListOf(0 until 2)
        var i = 2
        while (i + 3 < src.size) {
            if (src[i].u() != JPEG_MARKER_PREFIX) break
            val marker = src[i + 1].u()
            if (marker == SOS || marker == EOI) break
            val length = (src[i + 2].u() shl 8) or src[i + 3].u()
            val end = i + 2 + length
            if (length < 2 || end > src.size) break
            if (!isMetadataMarker(marker)) keep.add(i until end)
            i = end
        }
        keep.add(i until src.size)
        return assemble(src, keep)
    }

    private fun stripPng(src: ByteArray): ByteArray {
        val keep = mutableListOf(0 until 8)
        var i = 8
        while (i + 8 <= src.size) {
            val length = (src[i].u() shl 24) or (src[i + 1].u() shl 16) or (src[i + 2].u() shl 8) or src[i + 3].u()
            val end = i + 12 + length
            if (length < 0 || end > src.size) break
            val type = CharArray(4) { src[i + 4 + it].u().toChar() }.concatToString()
            if (type !in PNG_METADATA_CHUNKS) keep.add(i until end)
            i = end
        }
        keep.add(i until src.size)
        return assemble(src, keep)
    }

    private fun assemble(src: ByteArray, ranges: List<IntRange>): ByteArray {
        val out = ByteArray(ranges.sumOf { it.last - it.first + 1 })
        var at = 0
        for (r in ranges) {
            src.copyInto(out, at, r.first, r.last + 1)
            at += r.last - r.first + 1
        }
        return out
    }
}
