package dev.stapler.stelekit.ui.annotate

import kotlin.test.Test
import kotlin.test.assertEquals

class PixelBufferConverterTest {

    @Test
    fun flattenToOpaqueRgba_fullyOpaquePixel_passesThroughUnchanged() {
        val pixels = intArrayOf(0xFFFF0000.toInt())

        val result = flattenToOpaqueRgba(pixels, 1, 1)

        assertEquals(
            listOf(255.toByte(), 0.toByte(), 0.toByte(), 255.toByte()),
            result.toList(),
        )
    }

    @Test
    fun flattenToOpaqueRgba_semiTransparentPixel_compositesOntoBlack() {
        // alpha=128, r=255, g=0, b=0, straight alpha
        val pixels = intArrayOf(0x80FF0000.toInt())

        val result = flattenToOpaqueRgba(pixels, 1, 1)

        assertEquals(128.toByte(), result[0], "R should composite to 255 * 128 / 255 = 128")
        assertEquals(0.toByte(), result[1])
        assertEquals(0.toByte(), result[2])
        assertEquals(255.toByte(), result[3], "output alpha must always be opaque")
    }

    @Test
    fun flattenToOpaqueRgba_fullyTransparentPixel_compositesToBlack() {
        val pixels = intArrayOf(0x00FFFFFF)

        val result = flattenToOpaqueRgba(pixels, 1, 1)

        assertEquals(
            listOf(0.toByte(), 0.toByte(), 0.toByte(), 255.toByte()),
            result.toList(),
        )
    }

    @Test
    fun flattenToOpaqueRgba_multiplePixels_producesCorrectlyOrderedBuffer() {
        val pixels = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt())

        val result = flattenToOpaqueRgba(pixels, 2, 1)

        assertEquals(8, result.size)
        assertEquals(
            listOf(255.toByte(), 0.toByte(), 0.toByte(), 255.toByte()),
            result.toList().subList(0, 4),
        )
        assertEquals(
            listOf(0.toByte(), 255.toByte(), 0.toByte(), 255.toByte()),
            result.toList().subList(4, 8),
        )
    }
}
