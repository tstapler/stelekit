// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: desktop-only (allocates real ImageBitmaps)
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.graphics.ImageBitmap
import dev.stapler.stelekit.platform.image.ImageDownscalerPort
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Epic E5: a phone photo is reduced to the display cap before it is decoded, and decoded bitmaps stay within the sheet's budget. */
class SheetImageBitmapsTest {
    private class Downscaler(private val result: List<ByteArray?>?) : ImageDownscalerPort {
        val asked = mutableListOf<List<Int>>()

        override fun downscale(source: ByteArray, maxEdges: List<Int>, quality: Int): List<ByteArray?>? {
            asked += maxEdges
            return result
        }
    }

    private val original = ByteArray(8) { 1 }
    private val reduced = ByteArray(4) { 2 }

    private fun bitmaps(downscaler: ImageDownscalerPort, budget: Long = Long.MAX_VALUE, decode: (ByteArray) -> ImageBitmap?) =
        SheetImageBitmaps({ original }, budget, downscaler, decode, Dispatchers.Unconfined, Dispatchers.Unconfined)

    @Test
    fun image_should_DecodeTheReducedCopy_NotTheFullPhoto() {
        val downscaler = Downscaler(listOf(reduced))
        val seen = mutableListOf<ByteArray>()
        val held = bitmaps(downscaler) { seen += it; ImageBitmap(16, 8) }

        held.image("f")
        assertNotNull(held.image("f"))

        assertEquals(listOf(listOf(SheetImageBitmaps.DISPLAY_EDGE_PX)), downscaler.asked)
        assertContentEquals(reduced, seen.single())
    }

    @Test
    fun image_should_DecodeTheOriginal_When_ItIsAlreadySmallOrThePlatformCannotReduce() {
        for (answer in listOf(listOf<ByteArray?>(null), null)) {
            val seen = mutableListOf<ByteArray>()
            val held = bitmaps(Downscaler(answer)) { seen += it; ImageBitmap(4, 4) }
            held.image("f")
            assertContentEquals(original, seen.single())
        }
    }

    @Test
    fun image_should_ShowAPlaceholder_AndNotRetry_When_TheFileDoesNotDecode() {
        var calls = 0
        val held = bitmaps(Downscaler(listOf(reduced))) { calls++; null }
        assertNull(held.image("f"))
        assertNull(held.image("f"))
        assertNull(held.image("f"))
        assertEquals(1, calls)
    }

    @Test
    fun image_should_SurviveADecoderThatThrows_ForTheSakeOfAndroid() {
        val held = bitmaps(Downscaler(null)) { throw OutOfMemoryError("test") }
        assertNull(held.image("f"))
        assertNull(held.image("f"))
    }

    @Test
    fun image_should_EvictTheLeastRecentlyDrawn_When_OverBudget() {
        val bytes = MEGAPIXEL_SIDE.toLong() * MEGAPIXEL_SIDE * 4
        val held = SheetImageBitmaps({ original }, bytes * 2 + 1, Downscaler(null), { ImageBitmap(MEGAPIXEL_SIDE, MEGAPIXEL_SIDE) }, Dispatchers.Unconfined, Dispatchers.Unconfined)
        held.image("a")
        held.image("b")
        held.image("a")
        held.image("c")
        assertNotNull(held.heldSize("a"), "a was drawn most recently before c")
        assertNull(held.heldSize("b"), "b was the least recently drawn")
        assertNotNull(held.heldSize("c"))
    }

    @Test
    fun displayCap_should_KeepAPhotoUnderSixteenMebibytes() {
        val worst = SheetImageBitmaps.DISPLAY_EDGE_PX.toLong() * SheetImageBitmaps.DISPLAY_EDGE_PX * 4
        assertTrue(worst <= SIXTEEN_MIB, "a square photo at the cap is $worst bytes")
    }

    private companion object {
        const val MEGAPIXEL_SIDE = 1000
        const val SIXTEEN_MIB = 16L * 1024 * 1024
    }
}
