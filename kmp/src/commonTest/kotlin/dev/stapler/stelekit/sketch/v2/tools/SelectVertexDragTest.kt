// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dragging one vertex of the selected line from the Select tool, with a finger-sized grab area and point snapping. */
class SelectVertexDragTest {
    private val line = LineElement(ElementBase(elId("l1")), listOf(sp(0, 0), sp(100, 0), sp(100, 100)))
    private val other = filledRectEl("r", 200, 0, 40, 40)
    private val touch = PointerKind.Touch

    private fun selected() = SelectTool(listOf(elId("l1")), touchPresent = true)

    @Test
    fun drag_should_MoveOnlyThatVertex_When_PressedWithinFingerReach() {
        val ctx = testToolContext(sceneOfElements(line))
        val r = runScenario(selected(), ctx) { down(115, 10, kind = touch); move(135, 30, kind = touch); up(135, 30, kind = touch) }
        val cmd = assertIs<SetLinePointsCommand>(r.commands.single())
        assertEquals(listOf(sp(0, 0), sp(120, 20), sp(100, 100)), cmd.points)
    }

    @Test
    fun press_should_MissTheVertex_When_BeyondTheTouchTolerance() {
        val ctx = testToolContext(sceneOfElements(line))
        val r = runScenario(selected(), ctx) { down(160, 0, kind = touch); move(180, 20, kind = touch); up(180, 20, kind = touch) }
        assertTrue(r.commands.none { it is SetLinePointsCommand })
    }

    @Test
    fun drag_should_SnapToAPointOfAnotherShape_When_WithinReach() {
        val ctx = testToolContext(sceneOfElements(line, other))
        val r = runScenario(selected(), ctx) { down(100, 0, kind = touch); move(190, 8, kind = touch); up(190, 8, kind = touch) }
        assertEquals(sp(200, 0), assertIs<SetLinePointsCommand>(r.commands.single()).points[1])
    }

    @Test
    fun release_should_BeAClick_When_TheVertexBarelyMoved() {
        val ctx = testToolContext(sceneOfElements(line))
        val r = runScenario(selected(), ctx) { down(100, 0, kind = touch); up(101, 0, kind = touch) }
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun escape_should_AbandonTheDrag_WithNoCommand() {
        val ctx = testToolContext(sceneOfElements(line))
        val r = runScenario(selected(), ctx) { down(100, 0, kind = touch); move(150, 50, kind = touch); escape(); up(150, 50, kind = touch) }
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun vertex_should_WinOverTheBoxHandleItOverlaps() {
        val ctx = testToolContext(sceneOfElements(line))
        val r = runScenario(selected(), ctx) { down(0, 0, kind = touch); move(-30, -30, kind = touch); up(-30, -30, kind = touch) }
        assertEquals(sp(-30, -30), assertIs<SetLinePointsCommand>(r.commands.single()).points[0])
    }

    @Test
    fun press_should_NotGrabAVertex_When_TheLineIsNotSelected() {
        val ctx = testToolContext(sceneOfElements(line))
        val r = runScenario(SelectTool(touchPresent = true), ctx) { down(100, 0, kind = touch); move(150, 50, kind = touch); up(150, 50, kind = touch) }
        assertNull(r.commands.filterIsInstance<SetLinePointsCommand>().firstOrNull())
    }
}
