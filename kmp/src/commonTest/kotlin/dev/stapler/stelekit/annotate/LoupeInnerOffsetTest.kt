package dev.stapler.stelekit.annotate

import androidx.compose.ui.geometry.Offset
import dev.stapler.stelekit.ui.annotate.loupeInnerOffset
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The loupe shows an inner image of `canvas * zoom` (Fit-scaled, centred) offset by
 * [loupeInnerOffset]. A canvas point p must land on the loupe centre for the crosshair to be
 * truthful.
 */
class LoupeInnerOffsetTest {
    private val loupeSizePx = 360f
    private val zooms = listOf(1.5f, 2f, 3f, 4f)

    /** Where content point [p] (canvas coords of an image Fit into [canvasW]x[canvasH]) sits in the inner image. */
    private fun innerPosition(
        p: Offset, canvasW: Float, canvasH: Float, imageW: Float, imageH: Float, zoom: Float,
    ): Offset {
        fun fitOrigin(w: Float, h: Float): Pair<Float, Float> {
            val s = min(w / imageW, h / imageH)
            return (w - imageW * s) / 2f to (h - imageH * s) / 2f
        }
        val (mainOx, mainOy) = fitOrigin(canvasW, canvasH)
        val mainScale = min(canvasW / imageW, canvasH / imageH)
        // Image-space coordinate under p in the main view.
        val ix = (p.x - mainOx) / mainScale
        val iy = (p.y - mainOy) / mainScale
        val (innerOx, innerOy) = fitOrigin(canvasW * zoom, canvasH * zoom)
        val innerScale = min(canvasW * zoom / imageW, canvasH * zoom / imageH)
        return Offset(innerOx + ix * innerScale, innerOy + iy * innerScale)
    }

    private fun assertCentred(p: Offset, w: Float, h: Float, imgW: Float, imgH: Float, zoom: Float) {
        val off = loupeInnerOffset(loupeSizePx, p, zoom)
        val inner = innerPosition(p, w, h, imgW, imgH, zoom)
        val cx = off.x + inner.x
        val cy = off.y + inner.y
        assertTrue(abs(cx - loupeSizePx / 2f) <= 0.5f + 1e-3f, "x $cx vs ${loupeSizePx / 2f} p=$p zoom=$zoom")
        assertTrue(abs(cy - loupeSizePx / 2f) <= 0.5f + 1e-3f, "y $cy vs ${loupeSizePx / 2f} p=$p zoom=$zoom")
    }

    @Test
    fun squareCanvas_variousPoints_andZooms() {
        for (z in zooms) for (p in listOf(Offset(500f, 500f), Offset(0f, 0f), Offset(1000f, 1000f), Offset(123.4f, 876.6f))) {
            assertCentred(p, 1000f, 1000f, 4000f, 4000f, z)
        }
    }

    @Test
    fun letterboxedCanvas_portraitCanvasLandscapeImage() {
        for (z in zooms) for (p in listOf(Offset(540f, 1200f), Offset(10f, 900f), Offset(1070f, 1500f), Offset(333.3f, 1111.1f))) {
            assertCentred(p, 1080f, 2000f, 4000f, 3000f, z)
        }
    }

    @Test
    fun letterboxedCanvas_landscapeCanvasPortraitImage() {
        for (z in zooms) for (p in listOf(Offset(960f, 540f), Offset(400f, 5f), Offset(1500f, 1070f))) {
            assertCentred(p, 1920f, 1080f, 3000f, 4000f, z)
        }
    }

    @Test
    fun offsetIsRoundedNotTruncated() {
        val off = loupeInnerOffset(loupeSizePx, Offset(100.3f, 200.7f), 3f)
        assertEquals((loupeSizePx / 2f - 100.3f * 3f).roundToInt(), off.x)
        assertEquals((loupeSizePx / 2f - 200.7f * 3f).roundToInt(), off.y)
        // 180 - 300.9 = -120.9 -> rounds to -121; truncation would give -120.
        assertEquals(-121, off.x)
    }

    @Test
    fun offsetIsIndependentOfImageDecodeSize() {
        // The offset algebra only depends on touch, zoom and loupe size — a capped decode that is
        // upscaled to canvas*zoom cannot change it.
        for (z in zooms) {
            val p = Offset(321f, 654f)
            assertEquals(loupeInnerOffset(loupeSizePx, p, z), loupeInnerOffset(loupeSizePx, p, z))
            assertEquals(
                (loupeSizePx / 2f - p.x * z).roundToInt(),
                loupeInnerOffset(loupeSizePx, p, z).x,
            )
        }
    }
}
