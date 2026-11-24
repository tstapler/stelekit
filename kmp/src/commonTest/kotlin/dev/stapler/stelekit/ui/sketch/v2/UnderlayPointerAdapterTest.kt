// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.tools.InputType
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Epic E5: while an underlay tool is active, touches reach it (the loupe works) and two fingers move the photo, not the view. */
class UnderlayPointerAdapterTest {
    private fun raw(id: Int, phase: RawPhase, x: Double, y: Double) = RawPointer(id, PointerKind.Touch, phase, ScreenPx(x, y), null, false)

    private fun tool(actions: List<PointerAction>) = actions.filterIsInstance<PointerAction.ToolInput>().map { it.event }

    @Test
    fun oneFinger_should_ReachTheTool_WithFingerDrawingOff_WhenAnUnderlayToolIsActive() {
        val adapter = SketchPointerAdapter().also { it.underlayTool = true }
        val events = listOf(raw(1, RawPhase.Down, 10.0, 10.0), raw(1, RawPhase.Move, 40.0, 30.0), raw(1, RawPhase.Up, 40.0, 30.0)).flatMap { tool(adapter.onPointer(it)) }
        assertEquals(listOf(InputType.Down, InputType.Move, InputType.Up), events.map { it.type })
    }

    @Test
    fun oneFinger_should_StillPan_WhenNoUnderlayToolIsActive() {
        val adapter = SketchPointerAdapter()
        val actions = listOf(raw(1, RawPhase.Down, 10.0, 10.0), raw(1, RawPhase.Move, 40.0, 30.0)).flatMap { adapter.onPointer(it) }
        assertTrue(actions.none { it is PointerAction.ToolInput })
        assertTrue(actions.any { it is PointerAction.ViewChange })
    }

    @Test
    fun twoFingers_should_BothReachTheTool_AndNeverMoveTheView_WhenTheToolTakesEveryFinger() {
        val adapter = SketchPointerAdapter().also {
            it.underlayTool = true
            it.multiPointerTool = true
        }
        val before = adapter.viewport
        val actions = listOf(
            raw(1, RawPhase.Down, 100.0, 100.0), raw(2, RawPhase.Down, 200.0, 100.0),
            raw(1, RawPhase.Move, 90.0, 100.0), raw(2, RawPhase.Move, 230.0, 100.0),
            raw(1, RawPhase.Up, 90.0, 100.0), raw(2, RawPhase.Up, 230.0, 100.0),
        ).flatMap { adapter.onPointer(it) }
        assertTrue(actions.none { it is PointerAction.ViewChange }, "the pinch moved the photo, not the view")
        assertEquals(before, adapter.viewport)
        val seen = tool(actions)
        assertEquals(listOf(1, 2, 1, 2, 1, 2), seen.map { it.pointerId })
        assertEquals(listOf(InputType.Down, InputType.Down, InputType.Move, InputType.Move, InputType.Up, InputType.Up), seen.map { it.type })
    }

    @Test
    fun secondFinger_should_CarryOn_WhenTheFirstLiftsFirst() {
        val adapter = SketchPointerAdapter().also {
            it.underlayTool = true
            it.multiPointerTool = true
        }
        val actions = listOf(
            raw(1, RawPhase.Down, 100.0, 100.0), raw(2, RawPhase.Down, 200.0, 100.0), raw(1, RawPhase.Up, 100.0, 100.0),
            raw(2, RawPhase.Move, 230.0, 120.0), raw(2, RawPhase.Up, 230.0, 120.0),
        ).flatMap { adapter.onPointer(it) }
        val seen = tool(actions)
        assertEquals(listOf(InputType.Down, InputType.Down, InputType.Up, InputType.Move, InputType.Up), seen.map { it.type })
        assertEquals(listOf(1, 2, 1, 2, 2), seen.map { it.pointerId })
    }

    @Test
    fun aSecondFinger_should_StillPinchTheView_ForAToolThatTakesOneFinger() {
        val adapter = SketchPointerAdapter().also { it.underlayTool = true }
        val actions = listOf(
            raw(1, RawPhase.Down, 100.0, 100.0), raw(2, RawPhase.Down, 200.0, 100.0),
            raw(2, RawPhase.Move, 260.0, 100.0),
        ).flatMap { adapter.onPointer(it) }
        assertTrue(actions.any { it is PointerAction.ViewChange }, "calibration keeps pinch to zoom")
    }
}
