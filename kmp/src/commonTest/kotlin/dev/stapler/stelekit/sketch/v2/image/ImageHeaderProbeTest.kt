// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.image

import arrow.core.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Story 3.4.3: the pixel size comes from header bytes only, per format; svg and junk are unsupported. */
class ImageHeaderProbeTest {
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())

    private fun png(w: Int, h: Int): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52) + be32(w) + be32(h) + byteArrayOf(8, 6, 0, 0, 0)

    private fun gif(w: Int, h: Int): ByteArray = "GIF89a".encodeToByteArray() + le16(w) + le16(h) + byteArrayOf(0, 0, 0)

    private fun jpeg(w: Int, h: Int): ByteArray {
        val app0 = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 4, 0, 0)
        val sof = byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, (h ushr 8).toByte(), h.toByte(), (w ushr 8).toByte(), w.toByte(), 1, 1, 0x11, 0)
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + sof
    }

    private fun webpLossy(w: Int, h: Int): ByteArray =
        "RIFF".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8 ".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) +
            byteArrayOf(0, 0, 0, 0x9D.toByte(), 0x01, 0x2A) + le16(w) + le16(h)

    private fun webpLossless(w: Int, h: Int): ByteArray {
        val bits = (w - 1) or ((h - 1) shl 14)
        return "RIFF".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8L".encodeToByteArray() + byteArrayOf(0, 0, 0, 0, 0x2F) +
            le16(bits and 0xFFFF) + le16((bits ushr 16) and 0xFFFF)
    }

    private fun webpExtended(w: Int, h: Int): ByteArray {
        fun le24(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte())
        return "RIFF".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8X".encodeToByteArray() + byteArrayOf(10, 0, 0, 0) +
            byteArrayOf(0, 0, 0, 0) + le24(w - 1) + le24(h - 1)
    }

    private fun header(bytes: ByteArray) = (ImageHeaderProbe.probe(bytes) as Either.Right).value

    @Test
    fun probe_should_ReadSizeFromEveryFormat() {
        assertEquals(ImageHeader(ImageFormat.Png, 640, 480), header(png(640, 480)))
        assertEquals(ImageHeader(ImageFormat.Gif, 31, 17), header(gif(31, 17)))
        assertEquals(ImageHeader(ImageFormat.Jpeg, 1200, 800), header(jpeg(1200, 800)))
        assertEquals(ImageHeader(ImageFormat.Webp, 321, 123), header(webpLossy(321, 123)))
        assertEquals(ImageHeader(ImageFormat.Webp, 321, 123), header(webpLossless(321, 123)))
        assertEquals(ImageHeader(ImageFormat.Webp, 4000, 3000), header(webpExtended(4000, 3000)))
    }

    @Test
    fun pixels_should_NotOverflowInt() {
        assertEquals(3_600_000_000L, ImageHeader(ImageFormat.Png, 60_000, 60_000).pixels)
    }

    @Test
    fun probe_should_RefuseSvgAndJunk_AndTruncatedHeaders() {
        assertIs<Either.Left<ImageProbeError>>(ImageHeaderProbe.probe("<svg xmlns='http://www.w3.org/2000/svg'/>".encodeToByteArray()))
        assertEquals(ImageProbeError.Unsupported, (ImageHeaderProbe.probe(byteArrayOf()) as Either.Left).value)
        assertEquals(ImageProbeError.Truncated, (ImageHeaderProbe.probe(png(1, 1).copyOf(12)) as Either.Left).value)
        assertEquals(ImageProbeError.Truncated, (ImageHeaderProbe.probe(jpeg(1, 1).copyOf(5)) as Either.Left).value)
        assertEquals(ImageProbeError.ZeroSize, (ImageHeaderProbe.probe(png(0, 5)) as Either.Left).value)
    }

    @Test
    fun probe_should_NeverThrow_OnAnyPrefix() {
        val sources = listOf(png(9, 9), jpeg(9, 9), gif(9, 9), webpLossy(9, 9), webpLossless(9, 9), webpExtended(9, 9))
        for (full in sources) for (n in 0..full.size) ImageHeaderProbe.probe(full.copyOf(n))
    }
}
