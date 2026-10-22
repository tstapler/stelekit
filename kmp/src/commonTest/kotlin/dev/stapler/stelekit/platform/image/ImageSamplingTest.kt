// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.image

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageSamplingTest {
    @Test
    fun fiftyMegapixelPhotoIsCappedUnderBudget() {
        // 8160x6120 = 49.9 MP; a full decode is ~200 MB.
        assertEquals(199_756_800L, decodedBitmapBytes(8160, 6120))
        val sample = inSampleSizeFor(8160, 6120)
        assertEquals(2, sample)
        assertEquals(49_939_200L, decodedBitmapBytes(8160, 6120, sample))
    }

    @Test
    fun smallImageIsNotSampled() {
        assertEquals(1, inSampleSizeFor(1080, 1920))
        assertEquals(1, inSampleSizeFor(0, 0))
    }

    @Test
    fun loupeSizeIsCapped() {
        // 3x zoom of a 1080x2200 canvas is 3240x6600 = 85 MB.
        val (w, h) = capLongestEdge(3240, 6600, MAX_LOUPE_EDGE_PX)
        assertEquals(2048, h)
        assertTrue(w in 1000..1100, "aspect ratio preserved: $w")
        assertEquals(500 to 400, capLongestEdge(500, 400, MAX_LOUPE_EDGE_PX))
    }

    @Test
    fun sampledPixelsAlwaysWithinBudgetAndSampleIsMinimalPowerOfTwo() = runTest {
        checkAll(Arb.int(1, 20_000), Arb.int(1, 20_000)) { w, h ->
            val s = inSampleSizeFor(w, h)
            assertTrue(s >= 1 && (s and (s - 1)) == 0, "power of two: $s")
            assertTrue((w / s).toLong() * (h / s).toLong() <= MAX_DECODE_PIXELS, "within budget: $w x $h / $s")
            if (s > 1) {
                val half = s / 2
                assertTrue((w / half).toLong() * (h / half).toLong() > MAX_DECODE_PIXELS, "minimal sample")
            }
        }
    }

    @Test
    fun loupeDecodeIsCappedAndNeverBelowOnePixel() {
        assertEquals(800 to 1200, loupeDecodeSize(800, 1200, 1f))
        val (w, h) = loupeDecodeSize(1080, 2400, 3f)
        assertEquals(MAX_LOUPE_EDGE_PX, h)
        assertTrue(w in 1..MAX_LOUPE_EDGE_PX)
        assertEquals(1 to 1, loupeDecodeSize(0, 0, 3f))
    }
}
