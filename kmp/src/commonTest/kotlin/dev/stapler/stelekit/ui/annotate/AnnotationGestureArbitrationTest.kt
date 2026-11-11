package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnotationGestureArbitrationTest {
    @Test
    fun oneFingerBelongsToTheTool() {
        assertEquals(GestureOwner.TOOL, gestureOwner(GestureOwner.TOOL, 1))
    }

    @Test
    fun aSecondFingerHandsTheGestureToTheView() {
        assertEquals(GestureOwner.VIEW, gestureOwner(GestureOwner.TOOL, 2))
        assertEquals(GestureOwner.VIEW, gestureOwner(GestureOwner.TOOL, 3))
    }

    @Test
    fun liftingOneFingerOfAPinchNeverResumesTheTool() {
        var owner = GestureOwner.TOOL
        for (pressed in listOf(1, 2, 1, 0)) owner = gestureOwner(owner, pressed)
        assertEquals(GestureOwner.VIEW, owner)
    }

    @Test
    fun doubleTapNeedsTwoCloseTapsInTime() {
        val first = TapRecord(1_000, Offset(100f, 100f))
        assertTrue(isDoubleTap(first, TapRecord(1_250, Offset(110f, 105f)), 300, 40f))
        assertFalse(isDoubleTap(first, TapRecord(1_400, Offset(100f, 100f)), 300, 40f), "too slow")
        assertFalse(isDoubleTap(first, TapRecord(1_100, Offset(300f, 100f)), 300, 40f), "too far apart")
        assertFalse(isDoubleTap(null, TapRecord(1_100, Offset(100f, 100f)), 300, 40f), "no earlier tap")
        assertFalse(isDoubleTap(first, TapRecord(900, Offset(100f, 100f)), 300, 40f), "clock went backwards")
    }
}
