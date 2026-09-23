package dev.stapler.stelekit.ui.annotate

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageEncodeGuardsTest {

    @Test
    fun isValidBitmapSize_positiveDimensions_true() {
        assertTrue(isValidBitmapSize(1, 1))
        assertTrue(isValidBitmapSize(3000, 2000))
    }

    @Test
    fun isValidBitmapSize_zeroOrNegative_false() {
        assertFalse(isValidBitmapSize(0, 0))
        assertFalse(isValidBitmapSize(0, 10))
        assertFalse(isValidBitmapSize(10, 0))
        assertFalse(isValidBitmapSize(-1, 10))
    }

    @Test
    fun exceedsCanvasAreaCeiling_underCeiling_false() {
        assertFalse(exceedsCanvasAreaCeiling(3000, 2000)) // 6,000,000px
    }

    @Test
    fun exceedsCanvasAreaCeiling_atCeiling_false() {
        assertFalse(exceedsCanvasAreaCeiling(4096, 4096)) // exactly 16,777,216px
    }

    @Test
    fun exceedsCanvasAreaCeiling_overCeiling_true() {
        assertTrue(exceedsCanvasAreaCeiling(5000, 3356)) // ~16,780,000px
    }

    @Test
    fun exceedsCanvasAreaCeiling_largeDimensionsDoNotOverflowInt_true() {
        // Regression guard for the Int-overflow pitfall: width*height as Int would wrap
        // negative here; the function must multiply as Long.
        assertTrue(exceedsCanvasAreaCeiling(100_000, 100_000))
    }
}
