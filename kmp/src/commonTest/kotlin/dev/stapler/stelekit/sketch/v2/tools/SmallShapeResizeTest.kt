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
    fun aBoxWithNoArea_should_GetNoCornerHandles() {
        assertEquals(HandleTier.None, HandleLayout.compact(50.0, 0.0, 100.0, 0.0, 0.0, 1.0, floorPx = 44.0).tier)
    }
}
