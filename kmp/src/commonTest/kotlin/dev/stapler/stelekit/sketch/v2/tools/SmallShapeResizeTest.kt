// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.sketch.v2.tools

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A selected shape of any size on any screen density can be resized without being moved: its four corners keep a hit area (clamped
 * to a share of the shape so they never swallow it), and the middle of the shape still moves it.
 */
class SmallShapeResizeTest {
    private val touch = PointerKind.Touch
    private val densities = listOf(1.0, 2.6, 3.0)
    private val sizesDp = listOf(20, 60, 130)

    private fun units(dp: Int, density: Double) = (dp * density).roundToInt()

    private fun press(dp: Int, density: Double, at: (Int) -> Pair<Int, Int>, drag: Pair<Int, Int>): ScenarioResult {
        val side = units(dp, density)
        val ctx = testToolContext(sceneOfElements(filledRectEl("a", 0, 0, side, side))).copy(pxPerDp = density)
        val (x, y) = at(side)
        return runScenario(SelectTool(listOf(elId("a")), touchPresent = true), ctx) {
            down(x, y, kind = touch); move(x + drag.first, y + drag.second, kind = touch); up(x + drag.first, y + drag.second, kind = touch)
        }
    }

    @Test
    fun cornerPress_should_Resize_ForEveryShapeSizeAndDensity() {
        for (density in densities) for (dp in sizesDp) {
            val r = press(dp, density, { 0 to 0 }, drag = -10 to -10)
            assertTrue(r.commands.single() is TransformSelectionCommand, "${dp}dp at x$density: a corner press must resize, got ${r.commands}")
        }
    }

    @Test
    fun aPressJustInsideACorner_should_Resize_ForEveryShapeSizeAndDensity() {
        for (density in densities) for (dp in sizesDp) {
            val inset = (units(dp, density) * 0.1).roundToInt().coerceAtLeast(1)
            val r = press(dp, density, { inset to inset }, drag = 15 to 15)
            assertTrue(r.commands.single() is TransformSelectionCommand, "${dp}dp at x$density: a press $inset units in from a corner must resize, got ${r.commands}")
        }
    }

    @Test
    fun theMiddleOfTheShape_should_StillMoveIt_ForEveryShapeSizeAndDensity() {
        for (density in densities) for (dp in sizesDp) {
            val r = press(dp, density, { it / 2 to it / 2 }, drag = 20 to 20)
            assertTrue(r.commands.single() is MoveSelectionCommand, "${dp}dp at x$density: the middle must move the shape, got ${r.commands}")
        }
    }

    @Test
    fun compactCorners_should_ClampTheirReachToAShareOfTheShape() {
        val layout = HandleLayout.compact(30.0, 30.0, 60.0, 60.0, 0.0, 1.0, floorPx = 132.0)
        assertEquals(HandleTier.Compact, layout.tier)
        assertEquals(setOf(Handle.NW, Handle.NE, Handle.SE, Handle.SW), layout.handles.map { it.handle }.toSet())
        assertTrue(layout.handles.all { it.halfHit <= HandleLayout.COMPACT_REACH * 60.0 + 1e-9 }, "reach exceeds the clamp")
        assertEquals(null, layout.hit(30.0, 30.0), "the middle of a small shape is its body, not a handle")
    }

    @Test
    fun aMouse_should_GetNoCornerHandlesOnASmallShape_AndAPressNearACornerMovesIt() {
        val ctx = testToolContext(sceneOfElements(filledRectEl("a", 0, 0, 20, 20)))
        val tool = SelectTool(listOf(elId("a")))
        assertEquals(HandleTier.None, tool.handleLayout(ctx)?.tier, "the compact corners are for touch only")
        assertEquals(emptyList(), tool.handleLayout(ctx)?.handles)
        val r = runScenario(tool, ctx) { down(2, 2); move(15, 15); up(15, 15) }
        assertTrue(r.commands.single() is MoveSelectionCommand, "a mouse press near the corner moves the shape, got ${r.commands}")
    }

    @Test
    fun aCompactCorner_should_ReachOutwardLessThanInward_SoANeighbourStaysSelectable() {
        val layout = HandleLayout.compact(30.0, 30.0, 60.0, 60.0, 0.0, 1.0, floorPx = 132.0)
        val inward = HandleLayout.COMPACT_REACH * 60.0
        assertEquals(Handle.NW, layout.hit(inward * 0.9, inward * 0.9), "a press just inside the corner resizes")
        assertEquals(Handle.NW, layout.hit(-inward * HandleLayout.COMPACT_OUTWARD_SHARE * 0.9, 0.0), "a press just outside, within the outward cap, still resizes")
        assertEquals(null, layout.hit(-inward * 0.9, -inward * 0.9), "further outside is the neighbour's")
    }

    @Test
    fun aPressBesideASmallSelectedShape_should_SelectTheNeighbour_NotResizeIt() {
        val small = filledRectEl("a", 100, 100, 20, 20)
        val neighbour = filledRectEl("b", 70, 100, 28, 20)
        val ctx = testToolContext(sceneOfElements(small, neighbour))
        val r = runScenario(SelectTool(listOf(elId("a")), touchPresent = true), ctx) { down(96, 101, kind = touch); up(96, 101, kind = touch) }
        assertEquals(listOf("b"), (r.finalTool as SelectionAware).selection.map { it.value })
    }

    @Test
    fun aBoxWithNoArea_should_GetNoCornerHandles() {
        assertEquals(HandleTier.None, HandleLayout.compact(50.0, 0.0, 100.0, 0.0, 0.0, 1.0, floorPx = 44.0).tier)
    }
}
