// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.tools.InputEvent
import dev.stapler.stelekit.sketch.v2.tools.InputType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoubleClickDetectorTest {
    private val a = ElementId("a")
    private val b = ElementId("b")

    private fun press(at: Long, x: Int = 0, y: Int = 0) = InputEvent(InputType.Down, ScenePoint(SceneUnit.ofInt(x), SceneUnit.ofInt(y)), timestamp = at)

    @Test
    fun twoQuickPressesOnOneElement_should_BeADoubleClick_AndTheThirdStartsOver() {
        val detector = DoubleClickDetector()
        assertFalse(detector.press(press(0), a))
        assertTrue(detector.press(press(DoubleClickDetector.WINDOW_MS), a))
        assertFalse(detector.press(press(DoubleClickDetector.WINDOW_MS + 1), a), "a double click is spent once reported")
    }

    @Test
    fun aSlowPress_aDifferentElement_orADrift_should_NotCount() {
        val detector = DoubleClickDetector()
        detector.press(press(0), a)
        assertFalse(detector.press(press(DoubleClickDetector.WINDOW_MS + 1), a), "too slow")
        detector.press(press(1000), a)
        assertFalse(detector.press(press(1100), b), "another element")
        detector.press(press(2000), a)
        assertFalse(detector.press(press(2100, x = 100), a), "moved too far")
        detector.press(press(3000), null)
        assertEquals(false, detector.press(press(3100), null), "empty canvas never counts")
    }
}
