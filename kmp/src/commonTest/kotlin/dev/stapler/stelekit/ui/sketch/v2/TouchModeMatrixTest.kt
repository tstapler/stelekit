// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which one-finger drags reach a tool, and so can show the loupe, with Finger drawing OFF and ON. A touch drag the adapter
 * routes to a pan (never a tool) is not a precision drag, and the loupe follows `ToolInput` only, so it stays out of it.
 */
class TouchModeMatrixTest {
    private fun adapter(finger: Boolean, select: Boolean, emptyAt: Boolean) = SketchPointerAdapter(SketchViewport.of(), fingerDrawing = finger).also {
        it.selectTool = select
        it.drawingTool = !select
        it.touchTapsReachTool = select
        it.isEmptyCanvas = { emptyAt }
    }

    private fun raw(phase: RawPhase, x: Double) = RawPointer(1, PointerKind.Touch, phase, ScreenPx(x, 100.0))

    private fun drag(a: SketchPointerAdapter): List<PointerAction> =
        a.onPointer(raw(RawPhase.Down, 100.0)) + a.onPointer(raw(RawPhase.Move, 160.0)) + a.onPointer(raw(RawPhase.Move, 200.0))

    private fun reachesTool(actions: List<PointerAction>) = actions.any { it is PointerAction.ToolInput }

    @Test
    fun fingerOff_should_PanOnEveryTouchDrag_SoNoToolAndNoLoupe() {
        // A shape, a resize handle and a vertex are all "not empty canvas"; with Finger drawing OFF none of them reaches a tool.
        val onShape = drag(adapter(finger = false, select = true, emptyAt = false))
        assertFalse(reachesTool(onShape), "drag a shape, handle or vertex")
        assertTrue(onShape.all { it is PointerAction.ViewChange })
        assertFalse(reachesTool(drag(adapter(finger = false, select = true, emptyAt = true))), "empty canvas")
        assertFalse(reachesTool(drag(adapter(finger = false, select = false, emptyAt = true))), "a drawing tool")
    }

    @Test
    fun fingerOn_should_ReachTheTool_ForShapeHandleVertexAndDrawingDrags() {
        assertTrue(reachesTool(drag(adapter(finger = true, select = true, emptyAt = false))), "drag a shape, handle or vertex")
        assertTrue(reachesTool(drag(adapter(finger = true, select = false, emptyAt = true))), "a drawing tool draws")
    }

    @Test
    fun fingerOn_should_StillPanFromEmptyCanvas_WithSelect() {
        assertFalse(reachesTool(drag(adapter(finger = true, select = true, emptyAt = true))))
    }

    @Test
    fun aTap_should_NeverShowTheLoupe_EvenWhenItReachesTheTool() {
        val a = adapter(finger = false, select = true, emptyAt = false)
        var state = LoupeState()
        val actions = a.onPointer(raw(RawPhase.Down, 10.0)) + a.onPointer(raw(RawPhase.Up, 10.0))
        assertTrue(reachesTool(actions), "a Select tap reaches the tool as Down and Up")
        for (action in actions) {
            if (action is PointerAction.ToolInput) state = LoupeSpec.reduce(state, action.event, ScreenPx(10.0, 100.0), 1.0)
            assertFalse(state.visible)
        }
        assertEquals(LoupeState(), state)
    }

    @Test
    fun visibleRegion_should_SelectOnlyTheElementsUnderTheBubble() {
        val scene = (0 until GRID * GRID).fold(SketchSceneV2.empty()) { s, i -> s.add(filledRectEl("r$i", (i % GRID) * STEP, (i / GRID) * STEP, SIZE, SIZE)).getOrNull()!! }
        val index = SpatialIndex().also { it.rebuild(scene) }
        val vp = SketchViewport.of()
        val all = ScenePainter.visibleIds(index, vp, GRID * STEP.toDouble(), GRID * STEP.toDouble())
        val region = ScenePainter.visibleIdsIn(index, vp, LoupeSpec.visibleRegion(ScreenPx(STEP * 5.5, STEP * 5.5), 1.0))
        assertEquals(GRID * GRID, all.size)
        assertTrue(region.size <= REGION_ELEMENTS, "the loupe draws ${region.size} of ${all.size} elements")
    }

    private companion object {
        const val GRID = 30
        const val STEP = 100
        const val SIZE = 40
        const val REGION_ELEMENTS = 9
    }
}
