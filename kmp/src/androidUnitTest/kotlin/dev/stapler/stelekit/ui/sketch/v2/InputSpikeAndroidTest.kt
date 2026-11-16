// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.sketch.v2

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spike 0.1.4 (Task 0.1.4a), Android column: synthesised MotionEvents through a real Compose host under Robolectric.
 * Not a device run: framework-level stylus button remapping and palm rejection need Task 0.1.4d.
 * Findings are recorded in project_plans/excalidraw-sketch-engine/research/input-spike.md.
 */
@RunWith(RobolectricTestRunner::class)
class InputSpikeAndroidTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private data class Sample(val type: PointerType, val event: PointerEventType, val pressure: Float, val primary: Boolean, val secondary: Boolean, val tertiary: Boolean)

    private val log = mutableListOf<Sample>()

    private fun start() {
        rule.setContent {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.first()
                            log += Sample(c.type, e.type, c.pressure, e.buttons.isPrimaryPressed, e.buttons.isSecondaryPressed, e.buttons.isTertiaryPressed)
                        }
                    }
                },
            )
        }
        rule.waitForIdle()
    }

    private fun event(action: Int, toolType: Int, pressure: Float, buttonState: Int = 0, x: Float = 40f, y: Float = 40f): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure })
        return MotionEvent.obtain(now, now, action, 1, props, coords, 0, buttonState, 1f, 1f, 0, 0, 0, 0)
    }

    private fun send(e: MotionEvent) {
        val decor = rule.activity.window.decorView
        if (e.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER) && (e.action == MotionEvent.ACTION_HOVER_MOVE || e.action == MotionEvent.ACTION_HOVER_ENTER)) {
            decor.dispatchGenericMotionEvent(e)
        } else {
            decor.dispatchTouchEvent(e)
        }
        rule.waitForIdle()
    }

    private fun report(label: String) = println("INPUT-SPIKE android $label: " + log.joinToString(" | ") { "${it.event} type=${it.type} p=${it.pressure} btn=${it.primary}/${it.secondary}/${it.tertiary}" })

    @Test
    fun stylusReportsTypeStylusAndPressure() {
        start()
        send(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, 0.6f))
        send(event(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, 0.6f))
        report("stylus")
        val down = log.first { it.event == PointerEventType.Press }
        assertEquals(PointerType.Stylus, down.type)
        assertEquals(0.6f, down.pressure, 1e-6f)
    }

    @Test
    fun eraserEndReportsTypeEraser() {
        start()
        send(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_ERASER, 0.5f))
        send(event(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_ERASER, 0.5f))
        report("eraser")
        assertEquals(PointerType.Eraser, log.first { it.event == PointerEventType.Press }.type)
    }

    @Test
    fun barrelButtonIsExposedThroughButtons() {
        start()
        send(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, 0.4f, MotionEvent.BUTTON_STYLUS_PRIMARY))
        send(event(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, 0.4f))
        report("barrel BUTTON_STYLUS_PRIMARY")
        val down = log.first { it.event == PointerEventType.Press }
        // The measured mapping (which PointerButtons bit a stylus-primary press lands on) is recorded in input-spike.md.
        assertTrue(down.primary || down.secondary || down.tertiary, "no button bit visible for a stylus-primary press: $down")
    }

    @Test
    fun stylusHoverIsDelivered() {
        start()
        val hover = event(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.TOOL_TYPE_STYLUS, 0f).also { it.source = android.view.InputDevice.SOURCE_STYLUS }
        send(hover)
        report("stylus hover")
        assertTrue(log.any { it.type == PointerType.Stylus && it.event == PointerEventType.Move || it.event == PointerEventType.Enter }, log.toString())
    }

    @Test
    fun palmToolTypeIsRecorded() {
        start()
        send(event(MotionEvent.ACTION_DOWN, 5 /* TOOL_TYPE_PALM, API 31 */, 0.3f))
        send(event(MotionEvent.ACTION_UP, 5, 0.3f))
        report("palm toolType=5")
        // Recorded only: the palm tool type is not a distinct PointerType, so the adapter needs the raw MotionEvent (Task 3.1.2h).
        println("INPUT-SPIKE android palm -> PointerType=" + log.firstOrNull()?.type)
    }
}
