// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.image

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** E5 leftover: orientations 2..8 turned upright without a platform downscaler. */
class BitmapOrienterTest {
    private fun source(): ImageBitmap {
        val bmp = ImageBitmap(3, 2)
        val canvas = Canvas(bmp)
        for (y in 0 until 2) for (x in 0 until 3) {
            canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, Paint().apply { color = Color(red = x / 2f, green = y.toFloat(), blue = 0f) })
        }
        return bmp
    }

    @Test
    fun orient_should_ReturnTheSameBitmap_When_OrientationIsUpright() {
        val bmp = source()
        assertSame(bmp, BitmapOrienter.orient(bmp, ExifOrientationReader.transformFor(1)))
    }

    @Test
    fun orient_should_PlaceEveryPixelWhereTheModelSays_ForEveryExifOrientation() {
        val src = source().toPixelMap()
        for (orientation in 2..8) {
            val t = ExifOrientationReader.transformFor(orientation)
            val out = BitmapOrienter.orient(source(), t).toPixelMap()
            for (y in 0 until 2) for (x in 0 until 3) {
                val (dx, dy) = BitmapOrienter.destinationOf(t, 3, 2, x, y)
                assertEquals(src[x, y], out[dx, dy], "orientation $orientation pixel ($x,$y) -> ($dx,$dy)")
            }
        }
    }

    @Test
    fun orient_should_SwapWidthAndHeight_OnAQuarterTurn() {
        val out = BitmapOrienter.orient(source(), ExifOrientationReader.transformFor(6))
        assertEquals(2 to 3, out.width to out.height)
    }
}
