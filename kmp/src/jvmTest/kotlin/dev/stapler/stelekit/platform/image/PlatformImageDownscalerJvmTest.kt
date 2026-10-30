// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.image

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformImageDownscalerJvmTest {
    private fun jpeg(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    private fun dims(bytes: ByteArray): Pair<Int, Int> {
        val img = ImageIO.read(ByteArrayInputStream(bytes))
        return img.width to img.height
    }

    /** Inserts an EXIF APP1 segment carrying [orientation] right after SOI. */
    private fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = "MM".encodeToByteArray() + byteArrayOf(0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0)
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val len = payload.size + 2
        return jpeg.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xe1.toByte(), (len shr 8).toByte(), len.toByte()) +
            payload + jpeg.copyOfRange(2, jpeg.size)
    }

    @Test
    fun twentyMegapixelSourceYieldsBoundedThumbnailAndDisplay() {
        val source = jpeg(5000, 4000)
        val out = PlatformImageDownscaler.downscale(source, listOf(DISPLAY_MAX_EDGE_PX, THUMBNAIL_MAX_EDGE_PX))
        assertNotNull(out)
        val (dw, dh) = dims(out[0]!!)
        val (tw, th) = dims(out[1]!!)
        assertEquals(DISPLAY_MAX_EDGE_PX, maxOf(dw, dh))
        assertEquals(THUMBNAIL_MAX_EDGE_PX, maxOf(tw, th))
        assertTrue(dw.toDouble() / dh in 1.24..1.26, "aspect preserved: $dw x $dh")
    }

    @Test
    fun smallSourceIsNotUpscaledOrRewritten() {
        val out = PlatformImageDownscaler.downscale(jpeg(200, 100), listOf(DISPLAY_MAX_EDGE_PX, THUMBNAIL_MAX_EDGE_PX))
        assertNotNull(out)
        assertNull(out[0])
        assertNull(out[1])
    }

    @Test
    fun exifRotationIsAppliedToDerivatives() {
        val source = withOrientation(jpeg(2000, 1000), orientation = 6)
        val out = PlatformImageDownscaler.downscale(source, listOf(DISPLAY_MAX_EDGE_PX))
        assertNotNull(out)
        assertEquals(800 to 1600, dims(out[0]!!))
    }

    @Test
    fun garbageInputDoesNotThrow() {
        assertNull(PlatformImageDownscaler.downscale(ByteArray(64) { 3 }, listOf(THUMBNAIL_MAX_EDGE_PX)))
    }
}
