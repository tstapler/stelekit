// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExifOrientationTest {
    @Test
    fun fromTag_should_MapAllEightValues() {
        val expected = mapOf(
            1 to (0 to false), 2 to (0 to true), 3 to (180 to false), 4 to (180 to true),
            5 to (270 to true), 6 to (90 to false), 7 to (90 to true), 8 to (270 to false),
        )
        expected.forEach { (tag, want) ->
            val got = ExifOrientation.fromTag(tag)
            assertEquals(want.first, got.rotationDegrees, "rotation for $tag")
            assertEquals(want.second, got.flipped, "flip for $tag")
        }
    }

    @Test
    fun fromTag_should_BeUpright_When_TagIsUnknown() {
        assertEquals(ExifOrientation.UPRIGHT, ExifOrientation.fromTag(0))
        assertEquals(ExifOrientation.UPRIGHT, ExifOrientation.fromTag(99))
    }

    @Test
    fun orientedSize_should_SwapAxes_Only_ForQuarterTurns() {
        assertEquals(3000 to 4000, ExifOrientation.fromTag(6).orientedSize(4000, 3000))
        assertEquals(3000 to 4000, ExifOrientation.fromTag(8).orientedSize(4000, 3000))
        assertEquals(3000 to 4000, ExifOrientation.fromTag(5).orientedSize(4000, 3000))
        assertEquals(4000 to 3000, ExifOrientation.fromTag(3).orientedSize(4000, 3000))
        assertTrue(ExifOrientation.fromTag(7).swapsAxes)
        assertFalse(ExifOrientation.fromTag(2).swapsAxes)
    }
}
