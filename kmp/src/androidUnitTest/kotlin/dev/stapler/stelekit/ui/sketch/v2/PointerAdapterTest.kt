// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.stapler.stelekit.sketch.v2.tools.InputType
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.1.2 (UX-07, UX-101): synthesised MotionEvents with real tool types go through a Compose host and
 * [sketchPointerInput] into [SketchPointerAdapter]. Not a device run: framework stylus-button remapping and palm
 * rejection are Task 0.1.4d.
 */
@RunWith(RobolectricTestRunner::class)
class PointerAdapterTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val adapter = SketchPointerAdapter()
    private val actions = mutableListOf<PointerAction>()

    private fun start() {
        rule.setContent {
            Box(Modifier.fillMaxSize().sketchPointerInput(adapter) { actions += it })
        }
        rule.waitForIdle()
    }

    private fun motion(action: Int, toolType: Int, x: Float, y: Float, pressure: Float, buttonState: Int = 0): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure })
        return MotionEvent.obtain(now, now, action, 1, props, coords, 0, buttonState, 1f, 1f, 0, 0, 0, 0)
    }

    private fun send(e: MotionEvent) {
        rule.activity.window.decorView.dispatchTouchEvent(e)
        rule.waitForIdle()
    }

    private fun stroke(toolType: Int, buttonState: Int = 0) {
        send(motion(MotionEvent.ACTION_DOWN, toolType, 40f, 40f, 0.6f, buttonState))
        send(motion(MotionEvent.ACTION_MOVE, toolType, 70f, 60f, 0.7f, buttonState))
        send(motion(MotionEvent.ACTION_UP, toolType, 70f, 60f, 0.7f))
    }

    private fun tool() = actions.filterIsInstance<PointerAction.ToolInput>()

    @Test
    fun stylus_should_ActWithPressure_And_NotPan() {
        start()
        stroke(MotionEvent.TOOL_TYPE_STYLUS)
        val events = tool().map { it.event }
        assertEquals(listOf(InputType.Down, InputType.Move, InputType.Up), events.map { it.type })
        assertTrue(events.all { it.pointerKind == PointerKind.Stylus })
        assertEquals(0.6, events.first().pressure!!, 1e-6)
        assertEquals(0.0, adapter.viewport.panX)
    }

    @Test
    fun finger_should_Pan_WhenFingerDrawingIsOff_And_Draw_WhenOn() {
        start()
        stroke(MotionEvent.TOOL_TYPE_FINGER)
        assertTrue(tool().isEmpty())
        assertEquals(30.0, adapter.viewport.panX, 1e-6)
        adapter.fingerDrawing = true
        stroke(MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(listOf(InputType.Down, InputType.Move, InputType.Up), tool().map { it.event.type })
    }

    @Test
    fun eraserEnd_should_OverrideTheToolForTheStroke() {
        start()
        stroke(MotionEvent.TOOL_TYPE_ERASER)
        assertTrue(tool().isNotEmpty())
        assertTrue(tool().all { it.toolOverride == ToolId.Eraser && it.event.pointerKind == PointerKind.Eraser })
        actions.clear()
        stroke(MotionEvent.TOOL_TYPE_STYLUS)
        assertTrue(tool().all { it.toolOverride == null })
    }

    @Test
    fun barrelButton_should_OpenContextMenu_AndNotDraw() {
        start()
        stroke(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY)
        println("POINTER-ADAPTER barrel actions: $actions")
        assertTrue(actions.any { it is PointerAction.ContextMenu }, "barrel press gave no context menu: $actions")
        assertTrue(tool().isEmpty())
    }
}
