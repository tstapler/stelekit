// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.image

import kotlin.test.Test
import kotlin.test.assertEquals

/** The EXIF orientation mapping with hard-coded expectations, for a 3 x 2 image (source pixel (0,0) top-left, (2,0) top-right). */
class BitmapOrienterModelTest {
    private fun dest(orientation: Int, x: Int, y: Int) = BitmapOrienter.destinationOf(ExifOrientationReader.transformFor(orientation), 3, 2, x, y)

    @Test
    fun mirror_should_FlipLeftToRight_ForOrientation2() = assertEquals(2 to 0, dest(2, 0, 0))

    @Test
    fun halfTurn_should_SendTopLeftToBottomRight_ForOrientation3() = assertEquals(2 to 1, dest(3, 0, 0))

    @Test
    fun transpose_should_SwapAxes_ForOrientation5() = assertEquals(0 to 2, dest(5, 2, 0))

    @Test
    fun clockwiseQuarter_should_PutTopLeftAtTopRight_ForOrientation6() {
        assertEquals(1 to 0, dest(6, 0, 0))
        assertEquals(1 to 2, dest(6, 2, 0))
    }

    @Test
    fun counterClockwiseQuarter_should_PutTopLeftAtBottomLeft_ForOrientation8() = assertEquals(0 to 2, dest(8, 0, 0))

    @Test
    fun upright_should_LeaveEveryPixel_ForOrientation1() = assertEquals(1 to 1, dest(1, 1, 1))
}
