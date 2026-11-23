// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.snap.SnapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The preview's cursor is the snapped point the tool acts on, which is where the touch loupe puts its crosshair. */
class PreviewCursorTest {
    private val subject = filledRectEl("a", 0, 0, 200, 120)
    private val right = filledRectEl("right", 300, 0, 100, 120)
    private val ctx = testToolContext(sceneOfElements(subject, right), 1.0).copy(snap = SnapSettings(objects = true))

    @Test
    fun moveCursor_should_BeTheGrabbedPointAfterSnapping() {
        val r = runScenario(SelectTool(selection = listOf(elId("a"))), ctx) { down(100, 60); move(197, 60); up(197, 60) }
        val cursor = assertNotNull(r.previews[1]?.cursor)
        assertEquals(200.0, cursor.x.value, "pressed at 100, the move snaps to 100, so the grabbed point lands at 200")
        assertEquals(60.0, cursor.y.value)
    }

    @Test
    fun resizeCursor_should_BeThePointerOnTheSnappedEdge() {
        val r = runScenario(SelectTool(selection = listOf(elId("a"))), ctx) { down(200, 60); move(296, 60); up(296, 60) }
        assertEquals(300.0, assertNotNull(r.previews[1]?.cursor).x.value, "the edge at 296 snaps to the neighbour at 300")
    }

    @Test
    fun cursor_should_BeNull_When_TheToolShowsNoSnap() {
        val r = runScenario(RectangleTool(), ctx) { down(10, 10); move(50, 50); up(50, 50) }
        assertNull(r.previews[1]?.cursor)
    }
}
