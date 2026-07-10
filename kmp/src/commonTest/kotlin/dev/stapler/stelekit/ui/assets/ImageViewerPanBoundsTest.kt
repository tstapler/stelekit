package dev.stapler.stelekit.ui.assets

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [maxPan] — the pure pan-bound calculation extracted from the [ImageViewer]
 * composable so the `ContentScale.Fit` letterbox math can be verified without a Compose test
 * rule.
 */
class ImageViewerPanBoundsTest {

    private val container = IntSize(420, 420)

    @Test
    fun scaleOne_hasZeroPanBoundOnBothAxes() {
        // At scale = 1 the image exactly fills its rendered bounds — no room to pan regardless
        // of aspect ratio or letterboxing.
        val bound = maxPan(container, IntSize(420, 420), scale = 1f)
        assertEquals(0f, bound.x)
        assertEquals(0f, bound.y)
    }

    @Test
    fun squareImageInSquareContainer_noLetterbox_matchesContainerBasedBound() {
        // No letterboxing: rendered image bounds == container bounds, so the pan bound equals
        // the old container-based behavior.
        val bound = maxPan(container, IntSize(420, 420), scale = 2f)
        val expected = container.width * (2f - 1f) / 2f
        assertEquals(expected, bound.x)
        assertEquals(expected, bound.y)
    }

    @Test
    fun portraitImageInSquareContainer_letterboxesOnXAxis() {
        // A tall/narrow image (1:2) fit into a square container is letterboxed on the X axis —
        // rendered width is half the container width, height fills the container.
        val bound = maxPan(container, IntSize(210, 420), scale = 2f)
        val expectedX = 210f * (2f - 1f) / 2f
        val expectedY = 420f * (2f - 1f) / 2f
        assertEquals(expectedX, bound.x)
        assertEquals(expectedY, bound.y)
        assertEquals(true, bound.x < expectedY, "letterboxed X-axis pan bound must be smaller than the container-based Y bound")
    }

    @Test
    fun landscapeImageInSquareContainer_letterboxesOnYAxis() {
        // A wide/short image (2:1) fit into a square container is letterboxed on the Y axis.
        val bound = maxPan(container, IntSize(420, 210), scale = 2f)
        val expectedX = 420f * (2f - 1f) / 2f
        val expectedY = 210f * (2f - 1f) / 2f
        assertEquals(expectedX, bound.x)
        assertEquals(expectedY, bound.y)
    }

    @Test
    fun nullIntrinsicSize_fallsBackToContainerBasedBound() {
        // Image still loading — no letterbox correction possible yet, so fall back to the
        // pre-existing container-based behavior rather than clamping pan to zero.
        val bound = maxPan(container, null, scale = 3f)
        val expected = container.width * (3f - 1f) / 2f
        assertEquals(expected, bound.x)
        assertEquals(expected, bound.y)
    }

    @Test
    fun zeroContainerSize_returnsZeroBound() {
        val bound = maxPan(IntSize.Zero, IntSize(100, 100), scale = 2f)
        assertEquals(0f, bound.x)
        assertEquals(0f, bound.y)
    }
}
