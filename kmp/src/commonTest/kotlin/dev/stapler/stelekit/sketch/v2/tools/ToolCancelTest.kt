// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Story 3.1.1 (UX-08): a second pointer, Esc or a platform cancel abandons the gesture with no command and no undo entry. */
class ToolCancelTest {
    private val start = ScenarioRectTool()

    @Test
    fun drag_should_EmitOneAddCommand_When_ReleasedNormally() {
        val r = runScenario(start) { down(0, 0); move(10, 20); up(30, 40) }
        assertEquals(1, r.commands.size)
        val rect = assertIs<RectElement>(assertIs<AddElementCommand>(r.commands.single()).element)
        assertEquals(30.0, rect.width.value)
        assertEquals(40.0, rect.height.value)
        assertNull(r.previews.last())
        assertSame(start, r.finalTool)
    }

    @Test
    fun drag_should_ShowPreviewWithoutCommand_When_MidDrag() {
        val r = runScenario(start) { down(0, 0); move(10, 20) }
        assertTrue(r.commands.isEmpty())
        assertNotNull(r.previews.last())
    }

    @Test
    fun drag_should_CancelWithNoCommand_When_SecondPointerGoesDown() {
        val r = runScenario(start) { down(0, 0); move(10, 20); down(50, 50, pointer = 1) }
        assertTrue(r.commands.isEmpty())
        assertNull(r.previews.last())
        assertSame(start, r.finalTool)
    }

    @Test
    fun drag_should_CancelWithNoCommand_When_EscapeArrivesMidDrag() {
        val r = runScenario(start) { down(0, 0); move(10, 20); escape() }
        assertTrue(r.commands.isEmpty())
        assertNull(r.previews.last())
        assertSame(start, r.finalTool)
    }

    @Test
    fun drag_should_CancelWithNoCommand_When_PlatformCancels() {
        val r = runScenario(start) { down(0, 0); move(10, 20); cancel() }
        assertTrue(r.commands.isEmpty())
        assertSame(start, r.finalTool)
    }

    @Test
    fun cancelledGesture_should_NotCommitOnLaterRelease_And_ToolShouldDrawAgain() {
        val r = runScenario(start) {
            down(0, 0); move(10, 20); escape()
            up(30, 40)
            down(1, 1); up(5, 5)
        }
        assertEquals(1, r.commands.size)
    }

    @Test
    fun drag_should_IgnoreMovesOfAnotherPointer_When_NotADown() {
        val r = runScenario(start) { down(0, 0); move(99, 99, pointer = 1); up(10, 10) }
        val rect = assertIs<RectElement>(assertIs<AddElementCommand>(r.commands.single()).element)
        assertEquals(10.0, rect.width.value)
    }

    @Test
    fun cancel_should_LeaveSceneUntouched_When_GestureAbandoned() {
        val scene = SketchSceneV2.empty()
        val ctx = testToolContext(scene)
        runScenario(start, ctx) { down(0, 0); move(10, 20); escape() }
        assertSame(scene, ctx.scene)
        assertEquals(0, ctx.scene.size)
    }
}
