package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.ui.WindowSizeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadingScaleTest {
    private val bodySp = 14f

    @Test
    fun sizesNeverIncreaseWithLevelAndNeverDropBelowBody() {
        for (sc in WindowSizeClass.entries) {
            val sizes = (1..6).map { headingSpec(it, sc).fontSize.value }
            assertEquals(sizes.sortedDescending(), sizes, "non-increasing for $sc")
            assertTrue(sizes.all { it >= bodySp }, "floor at body for $sc")
        }
    }

    @Test
    fun compactStaysUnderTwiceBodyAndExpandedFollowsLogseqH1() {
        assertTrue(headingSpec(1, WindowSizeClass.COMPACT).fontSize.value < 2 * bodySp)
        assertEquals(2 * bodySp, headingSpec(1, WindowSizeClass.EXPANDED).fontSize.value)
    }

    @Test
    fun lineHeightExceedsFontSizeAndOutOfRangeLevelsClamp() {
        for (sc in WindowSizeClass.entries) for (l in 1..6) {
            val s = headingSpec(l, sc)
            assertTrue(s.lineHeight.value > s.fontSize.value)
        }
        assertEquals(headingSpec(1, WindowSizeClass.COMPACT), headingSpec(0, WindowSizeClass.COMPACT))
        assertEquals(headingSpec(6, WindowSizeClass.COMPACT), headingSpec(9, WindowSizeClass.COMPACT))
    }
}
