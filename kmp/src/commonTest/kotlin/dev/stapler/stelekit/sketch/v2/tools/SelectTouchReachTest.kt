// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A touch target stated in dp must be the same physical size on every screen density. */
class SelectTouchReachTest {
    private val touch = PointerKind.Touch
    private val box = filledRectEl("a", 0, 0, 300, 300)
    private val line = LineElement(ElementBase(elId("l1")), listOf(sp(0, 0), sp(100, 0)))

    private fun ctx(density: Double, vararg els: dev.stapler.stelekit.sketch.v2.scene.SceneElement) =
        testToolContext(sceneOfElements(*els)).copy(pxPerDp = density)

    @Test
    fun cornerHandle_should_ReachFurtherIn_When_TheScreenIsDenser() {
        val selected = SelectTool(listOf(elId("a")), touchPresent = true)
        val dense = runScenario(selected, ctx(3.0, box)) { down(40, 40, kind = touch); move(10, 10, kind = touch); up(10, 10, kind = touch) }
        assertTrue(dense.commands.single() is TransformSelectionCommand, "a 3x screen reaches the corner handle from 40 units away")
        val sparse = runScenario(selected, ctx(1.0, box)) { down(40, 40, kind = touch); move(10, 10, kind = touch); up(10, 10, kind = touch) }
        assertIs<MoveSelectionCommand>(sparse.commands.single(), "a 1x screen does not")
    }

    @Test
    fun tap_should_SelectALineFromAFingerWidthAway_OnATouchScreen() {
        val dense = runScenario(SelectTool(touchPresent = true), ctx(3.0, line)) { down(50, 25, kind = touch); up(50, 25, kind = touch) }
        assertEquals(listOf("l1"), (dense.finalTool as SelectionAware).selection.map { it.value })
        val mouse = runScenario(SelectTool(), ctx(3.0, line)) { down(50, 25); up(50, 25) }
        assertTrue((mouse.finalTool as SelectionAware).selection.isEmpty(), "a mouse keeps the fine reach")
    }

    @Test
    fun vertexGrab_should_ReachFurther_When_TheScreenIsDenser() {
        val selected = SelectTool(listOf(elId("l1")), touchPresent = true)
        val dense = runScenario(selected, ctx(3.0, line)) { down(130, 0, kind = touch); move(150, 20, kind = touch); up(150, 20, kind = touch) }
        assertTrue(dense.commands.single() is SetLinePointsCommand, "33 units is within a 3x screen's 66 px reach")
        val sparse = runScenario(selected, ctx(1.0, line)) { down(130, 0, kind = touch); move(150, 20, kind = touch); up(150, 20, kind = touch) }
        assertTrue(sparse.commands.none { it is SetLinePointsCommand })
    }
}
