// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.tools.AddElementCommand
import dev.stapler.stelekit.sketch.v2.tools.InputType
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.tools.ScenarioRectTool
import dev.stapler.stelekit.sketch.v2.tools.Tool
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.tools.testToolContext
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.1.2 (UX-07, UX-08, UX-09, UX-101): touch navigates, stylus and mouse act, eraser end and barrel button map. */
class SketchPointerAdapterTest {
    private fun at(x: Double, y: Double) = ScreenPx(x, y)

    private fun raw(id: Int, kind: PointerKind, phase: RawPhase, x: Double, y: Double, secondary: Boolean = false, pressure: Double? = null) =
        RawPointer(id, kind, phase, at(x, y), pressure, secondary)

    /** Runs [pointers] through [adapter], drives a rectangle tool with every ToolInput, and returns what happened. */
    private class Run(val adapter: SketchPointerAdapter) {
        val actions = mutableListOf<PointerAction>()
        var tool: Tool = ScenarioRectTool()
        val commands = mutableListOf<Any>()
        private val ctx = testToolContext()

        fun feed(vararg pointers: RawPointer) {
            for (p in pointers) {
                for (a in adapter.onPointer(p)) {
                    actions += a
                    if (a is PointerAction.ToolInput) {
                        val r = tool.onEvent(a.event, ctx)
                        r.command?.let { commands += it }
                        r.next?.let { tool = it }
                    }
                }
            }
        }
    }

    @Test
    fun touchDrag_should_PanAndCreateNothing_When_FingerDrawingOff() {
        val run = Run(SketchPointerAdapter())
        run.feed(
            raw(1, PointerKind.Touch, RawPhase.Down, 100.0, 100.0),
            raw(1, PointerKind.Touch, RawPhase.Move, 130.0, 90.0),
            raw(1, PointerKind.Touch, RawPhase.Up, 130.0, 90.0),
        )
        assertTrue(run.commands.isEmpty())
        assertTrue(run.actions.none { it is PointerAction.ToolInput })
        assertEquals(30.0, run.adapter.viewport.panX)
        assertEquals(-10.0, run.adapter.viewport.panY)
    }

    @Test
    fun stylusDrag_should_CreateOneRectangle_And_NotPan() {
        val run = Run(SketchPointerAdapter())
        run.feed(
            raw(1, PointerKind.Stylus, RawPhase.Down, 10.0, 10.0, pressure = 0.4),
            raw(1, PointerKind.Stylus, RawPhase.Move, 40.0, 30.0, pressure = 0.5),
            raw(1, PointerKind.Stylus, RawPhase.Up, 40.0, 30.0, pressure = 0.5),
        )
        val rect = assertIs<RectElement>(assertIs<AddElementCommand>(run.commands.single()).element)
        assertEquals(30.0, rect.width.value)
        assertEquals(0.0, run.adapter.viewport.panX)
        assertEquals(0.4, assertIs<PointerAction.ToolInput>(run.actions.first()).event.pressure)
    }

    @Test
    fun mouse_should_ReportNoPressure_And_FingerDrawingOn_should_MakeTouchDraw() {
        val mouse = Run(SketchPointerAdapter())
        mouse.feed(raw(1, PointerKind.Mouse, RawPhase.Down, 1.0, 1.0, pressure = 1.0))
        assertNull(assertIs<PointerAction.ToolInput>(mouse.actions.single()).event.pressure)

        val finger = Run(SketchPointerAdapter(fingerDrawing = true))
        finger.feed(
            raw(1, PointerKind.Touch, RawPhase.Down, 0.0, 0.0),
            raw(1, PointerKind.Touch, RawPhase.Move, 20.0, 20.0),
            raw(1, PointerKind.Touch, RawPhase.Up, 20.0, 20.0),
        )
        assertEquals(1, finger.commands.size)
    }

    @Test
    fun secondFinger_should_CancelPreview_ThenPinchPan_When_FingerDrawingOn() {
        val run = Run(SketchPointerAdapter(fingerDrawing = true))
        run.feed(
            raw(1, PointerKind.Touch, RawPhase.Down, 100.0, 100.0),
            raw(1, PointerKind.Touch, RawPhase.Move, 120.0, 120.0),
            raw(2, PointerKind.Touch, RawPhase.Down, 200.0, 100.0),
            raw(2, PointerKind.Touch, RawPhase.Move, 300.0, 100.0),
            raw(1, PointerKind.Touch, RawPhase.Up, 120.0, 120.0),
            raw(2, PointerKind.Touch, RawPhase.Up, 300.0, 100.0),
        )
        assertTrue(run.commands.isEmpty(), "second finger must cancel the draw")
        assertTrue(run.adapter.viewport.zoom > 1.0, "spreading two fingers zooms in")
        // The tool is back to Idle and a fresh one-finger draw works.
        run.feed(raw(3, PointerKind.Touch, RawPhase.Down, 0.0, 0.0), raw(3, PointerKind.Touch, RawPhase.Up, 10.0, 10.0))
        assertEquals(1, run.commands.size)
    }

    @Test
    fun touch_should_BeIgnored_While_StylusStrokeIsActive() {
        val run = Run(SketchPointerAdapter(fingerDrawing = true))
        run.feed(
            raw(1, PointerKind.Stylus, RawPhase.Down, 10.0, 10.0),
            raw(9, PointerKind.Touch, RawPhase.Down, 500.0, 500.0),
            raw(9, PointerKind.Touch, RawPhase.Move, 600.0, 600.0),
            raw(9, PointerKind.Touch, RawPhase.Up, 600.0, 600.0),
            raw(1, PointerKind.Stylus, RawPhase.Up, 50.0, 50.0),
        )
        val rect = assertIs<RectElement>(assertIs<AddElementCommand>(run.commands.single()).element)
        assertEquals(40.0, rect.width.value)
        assertEquals(1.0, run.adapter.viewport.zoom)
        assertEquals(0.0, run.adapter.viewport.panX)
    }

    @Test
    fun eraserEnd_should_OverrideToolForOneStroke_And_RestoreAfterPenUp() {
        val adapter = SketchPointerAdapter()
        val down = adapter.onPointer(raw(1, PointerKind.Eraser, RawPhase.Down, 5.0, 5.0, pressure = 0.5))
        val move = adapter.onPointer(raw(1, PointerKind.Eraser, RawPhase.Move, 9.0, 9.0))
        val up = adapter.onPointer(raw(1, PointerKind.Eraser, RawPhase.Up, 9.0, 9.0))
        val next = adapter.onPointer(raw(2, PointerKind.Stylus, RawPhase.Down, 1.0, 1.0))
        for (a in down + move + up) assertEquals(ToolId.Eraser, assertIs<PointerAction.ToolInput>(a).toolOverride)
        assertEquals(PointerKind.Eraser, assertIs<PointerAction.ToolInput>(down.single()).event.pointerKind)
        assertNull(assertIs<PointerAction.ToolInput>(next.single()).toolOverride)
    }

    @Test
    fun barrelButtonAndRightClick_should_OpenContextMenu_AndNotDraw() {
        for (kind in listOf(PointerKind.Stylus, PointerKind.Mouse)) {
            val run = Run(SketchPointerAdapter(SketchViewport.of(zoom = 2.0)))
            run.feed(
                raw(1, kind, RawPhase.Down, 40.0, 20.0, secondary = true),
                raw(1, kind, RawPhase.Move, 80.0, 80.0, secondary = true),
                raw(1, kind, RawPhase.Up, 80.0, 80.0),
            )
            val menu = assertIs<PointerAction.ContextMenu>(run.actions.single())
            assertEquals(20.0, menu.at.x.value)
            assertEquals(10.0, menu.at.y.value)
            assertTrue(run.commands.isEmpty())
        }
    }

    @Test
    fun touchBarrel_should_NotBeSecondaryClick() {
        val adapter = SketchPointerAdapter()
        assertTrue(adapter.onPointer(raw(1, PointerKind.Touch, RawPhase.Down, 0.0, 0.0, secondary = true)).none { it is PointerAction.ContextMenu })
    }

    @Test
    fun escape_should_AbandonGesture_And_LaterUpShouldNotCommit() {
        val run = Run(SketchPointerAdapter())
        run.feed(raw(1, PointerKind.Mouse, RawPhase.Down, 0.0, 0.0), raw(1, PointerKind.Mouse, RawPhase.Move, 20.0, 20.0))
        for (a in run.adapter.onEscape()) {
            assertEquals(InputType.Escape, assertIs<PointerAction.ToolInput>(a).event.type)
            run.tool.onEvent(a.event, testToolContext()).next?.let { run.tool = it }
        }
        run.feed(raw(1, PointerKind.Mouse, RawPhase.Up, 20.0, 20.0))
        assertTrue(run.commands.isEmpty())
    }

    @Test
    fun wheel_should_ZoomAtPointer_WithModifier_And_PanOtherwise_WithUnits() {
        val adapter = SketchPointerAdapter(SketchViewport.of(zoom = 1.0, panX = 100.0, panY = 100.0))
        val anchor = at(300.0, 200.0)
        val sceneBefore = adapter.viewport.toScene(anchor)
        adapter.onWheel(WheelInput(anchor, 0.0, -100.0, zoomModifier = true))
        assertTrue(adapter.viewport.zoom > 1.0)
        assertEquals(sceneBefore.x.value, adapter.viewport.toScene(anchor).x.value, 1e-9)

        val pan = SketchPointerAdapter(SketchViewport.of())
        pan.onWheel(WheelInput(anchor, 0.0, 3.0, WheelUnit.Line))
        assertEquals(-48.0, pan.viewport.panY)
        pan.pageSizePx = 500.0
        pan.onWheel(WheelInput(anchor, 1.0, 0.0, WheelUnit.Page))
        assertEquals(-500.0, pan.viewport.panX)
    }

    @Test
    fun palmContact_should_NeverStartOrMoveAGesture_WithFingerDrawingOn() {
        val adapter = SketchPointerAdapter(fingerDrawing = true)
        val palm = RawPointer(1, PointerKind.Touch, RawPhase.Down, at(10.0, 10.0), palm = true)
        assertTrue(adapter.onPointer(palm).isEmpty())
        assertTrue(adapter.onPointer(palm.copy(phase = RawPhase.Move, position = at(40.0, 40.0))).isEmpty())
        assertTrue(adapter.onPointer(palm.copy(phase = RawPhase.Up)).isEmpty())
        val finger = adapter.onPointer(raw(2, PointerKind.Touch, RawPhase.Down, 50.0, 50.0))
        assertEquals(InputType.Down, (finger.single() as PointerAction.ToolInput).event.type)
    }

    @Test
    fun fingerTurnedPalm_should_CancelTheToolGesture_AndLeaveTheAdapterUsable() {
        val adapter = SketchPointerAdapter(fingerDrawing = true)
        adapter.onPointer(raw(1, PointerKind.Touch, RawPhase.Down, 10.0, 10.0))
        val cancel = adapter.onPointer(RawPointer(1, PointerKind.Touch, RawPhase.Cancel, at(10.0, 10.0), palm = true))
        assertEquals(InputType.Cancel, (cancel.single() as PointerAction.ToolInput).event.type)
        assertEquals(1, adapter.onPointer(raw(3, PointerKind.Touch, RawPhase.Down, 20.0, 20.0)).size)
    }

    @Test
    fun handTool_should_PanWithAnyPointer_AndNeverReachTheTool() {
        val adapter = SketchPointerAdapter(handTool = true)
        val actions = listOf(
            raw(1, PointerKind.Mouse, RawPhase.Down, 100.0, 100.0),
            raw(1, PointerKind.Mouse, RawPhase.Move, 120.0, 110.0),
            raw(1, PointerKind.Mouse, RawPhase.Up, 120.0, 110.0),
        ).flatMap { adapter.onPointer(it) }
        assertTrue(actions.none { it is PointerAction.ToolInput })
        assertEquals(20.0, adapter.viewport.panX)
        assertEquals(10.0, adapter.viewport.panY)
    }

    @Test
    fun touchTap_should_ReachTheTool_Only_When_TheToolTakesTaps_And_TheFingerStaysPut() {
        val taps = SketchPointerAdapter(touchTapsReachTool = true)
        val tapped = listOf(
            raw(1, PointerKind.Touch, RawPhase.Down, 100.0, 100.0),
            raw(1, PointerKind.Touch, RawPhase.Up, 102.0, 101.0),
        ).flatMap { taps.onPointer(it) }
        assertEquals(listOf(InputType.Down, InputType.Up), tapped.map { (it as PointerAction.ToolInput).event.type })
        val dragged = listOf(
            raw(1, PointerKind.Touch, RawPhase.Down, 100.0, 100.0),
            raw(1, PointerKind.Touch, RawPhase.Move, 160.0, 100.0),
            raw(1, PointerKind.Touch, RawPhase.Up, 160.0, 100.0),
        ).flatMap { taps.onPointer(it) }
        assertTrue(dragged.none { it is PointerAction.ToolInput })
        val off = SketchPointerAdapter()
        val ignored = listOf(
            raw(1, PointerKind.Touch, RawPhase.Down, 100.0, 100.0),
            raw(1, PointerKind.Touch, RawPhase.Up, 100.0, 100.0),
        ).flatMap { off.onPointer(it) }
        assertTrue(ignored.isEmpty())
    }

    @Test
    fun hover_should_ReachTheTool_OnlyForMouseAndStylus() {
        val adapter = SketchPointerAdapter()
        assertEquals(1, adapter.onPointer(raw(1, PointerKind.Mouse, RawPhase.Hover, 5.0, 5.0)).size)
        assertTrue(adapter.onPointer(raw(2, PointerKind.Touch, RawPhase.Hover, 5.0, 5.0)).isEmpty())
    }

    private fun at(kind: PointerKind, phase: RawPhase, id: Int, t: Long, x: Double = 50.0, y: Double = 50.0) =
        RawPointer(id, kind, phase, at(x, y), timestamp = t)

    @Test
    fun stylusHover_should_ReachTheTool_WithoutContact() {
        val adapter = SketchPointerAdapter()
        val out = adapter.onPointer(at(PointerKind.Stylus, RawPhase.Hover, 1, 0L))
        assertEquals(InputType.Move, (out.single() as PointerAction.ToolInput).event.type)
        assertEquals(PointerKind.Stylus, (out.single() as PointerAction.ToolInput).event.pointerKind)
    }

    @Test
    fun touch_should_BeIgnored_While_ThePenHovers_And_PanAfterItLeaves() {
        val adapter = SketchPointerAdapter()
        adapter.onPointer(at(PointerKind.Stylus, RawPhase.Hover, 1, 100L))
        val ignored = listOf(
            at(PointerKind.Touch, RawPhase.Down, 2, 150L, 100.0, 100.0),
            at(PointerKind.Touch, RawPhase.Move, 2, 160L, 140.0, 100.0),
            at(PointerKind.Touch, RawPhase.Up, 2, 170L, 140.0, 100.0),
        ).flatMap { adapter.onPointer(it) }
        assertTrue(ignored.isEmpty())
        assertEquals(0.0, adapter.viewport.panX)
        adapter.onPointer(at(PointerKind.Stylus, RawPhase.Cancel, 1, 200L))
        val pans = listOf(
            at(PointerKind.Touch, RawPhase.Down, 3, 250L, 100.0, 100.0),
            at(PointerKind.Touch, RawPhase.Move, 3, 260L, 140.0, 100.0),
        ).flatMap { adapter.onPointer(it) }
        assertTrue(pans.single() is PointerAction.ViewChange)
        assertEquals(40.0, adapter.viewport.panX)
    }

    @Test
    fun touch_should_Pan_When_ThePenWentSilentLongerThanTheRangeWindow() {
        val adapter = SketchPointerAdapter()
        adapter.onPointer(at(PointerKind.Stylus, RawPhase.Hover, 1, 0L))
        adapter.onPointer(at(PointerKind.Touch, RawPhase.Down, 2, 10_000L, 100.0, 100.0))
        adapter.onPointer(at(PointerKind.Touch, RawPhase.Move, 2, 10_010L, 120.0, 100.0))
        assertEquals(20.0, adapter.viewport.panX)
    }

    @Test
    fun touchAlreadyPanning_should_KeepPanning_When_ThePenArrives() {
        val adapter = SketchPointerAdapter()
        adapter.onPointer(at(PointerKind.Touch, RawPhase.Down, 2, 0L, 100.0, 100.0))
        adapter.onPointer(at(PointerKind.Stylus, RawPhase.Hover, 1, 10L))
        adapter.onPointer(at(PointerKind.Touch, RawPhase.Move, 2, 20L, 130.0, 100.0))
        assertEquals(30.0, adapter.viewport.panX)
    }

    @Test
    fun touch_should_BeIgnored_While_EraserEndHovers() {
        val adapter = SketchPointerAdapter()
        adapter.onPointer(at(PointerKind.Eraser, RawPhase.Hover, 1, 0L))
        assertTrue(adapter.onPointer(at(PointerKind.Touch, RawPhase.Down, 2, 5L)).isEmpty())
    }

    @Test
    fun mouseHover_should_NotRejectTouch() {
        val adapter = SketchPointerAdapter()
        adapter.onPointer(at(PointerKind.Mouse, RawPhase.Hover, 1, 0L))
        adapter.onPointer(at(PointerKind.Touch, RawPhase.Down, 2, 5L, 100.0, 100.0))
        adapter.onPointer(at(PointerKind.Touch, RawPhase.Move, 2, 6L, 110.0, 100.0))
        assertEquals(10.0, adapter.viewport.panX)
    }
}
