// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.2.3 (UX-08, UX-101): the Eraser, Frame and Hand tools. */
class EraserFrameToolTest {
    private val r1 = filledRectEl("r1", 0, 0, 40, 40)
    private val r2 = filledRectEl("r2", 60, 0, 40, 40)
    private val r3 = filledRectEl("r3", 20, 20, 30, 30)

    @Test
    fun eraserStroke_should_DeleteEveryTouchedElementInOneTransaction() {
        val scene = sceneOfElements(r1, r2, filledRectEl("far", 0, 200, 40, 40))
        val ctx = testToolContext(scene)
        val r = runScenario(EraserTool(), ctx) { down(5, 20); move(50, 20); up(90, 20) }
        val cmd = assertIs<EraseCommand>(r.commands.single())
        assertEquals(setOf(elId("r1"), elId("r2")), cmd.ids.toSet())
        val history = commitToolCommand(SketchDocumentV2(scene), cmd)
        assertEquals(1, history.present.scene.size)
        assertEquals(1, history.undoDepth)
        assertEquals(3, history.undo().present.scene.size)
    }

    @Test
    fun eraserPreview_should_ShowTouchedElementsDashedAt30Percent_AndDeleteNothingUntilRelease() {
        val ctx = testToolContext(sceneOfElements(r1, r2))
        val r = runScenario(EraserTool(), ctx) { down(5, 20); move(70, 20) }
        assertTrue(r.commands.isEmpty())
        val shown = assertNotNull(r.previews.last()).elements
        assertEquals(setOf(elId("r1"), elId("r2")), shown.map { it.id }.toSet())
        assertTrue(shown.all { it.base.opacity == 30 && it.base.style.strokeStyle == StrokeStyle.Dashed })
    }

    @Test
    fun eraser_should_RestoreEverything_When_EscapeSecondPointerOrCancelArrives() {
        val ctx = testToolContext(sceneOfElements(r1, r2))
        val esc = runScenario(EraserTool(), ctx) { down(5, 20); move(70, 20); escape() }
        val second = runScenario(EraserTool(), ctx) { down(5, 20); move(70, 20); down(1, 1, pointer = 1) }
        val platform = runScenario(EraserTool(), ctx) { down(5, 20); move(70, 20); cancel() }
        for (r in listOf(esc, second, platform)) {
            assertTrue(r.commands.isEmpty())
            assertNull(r.previews.last())
        }
    }

    @Test
    fun eraserTap_should_EraseOnlyTheTopmostElement() {
        val ctx = testToolContext(sceneOfElements(r1, r3))
        val r = runScenario(EraserTool(), ctx) { down(30, 30, kind = PointerKind.Touch); up(30, 30, kind = PointerKind.Touch) }
        assertEquals(listOf(elId("r3")), assertIs<EraseCommand>(r.commands.single()).ids)
    }

    @Test
    fun eraser_should_SkipLockedElements_AndReportHowManyWereLeft() {
        val locked = filledRectEl("locked", 60, 0, 40, 40, RectSpec(locked = true))
        val ctx = testToolContext(sceneOfElements(r1, locked))
        val r = runScenario(EraserTool(), ctx) { down(5, 20); move(70, 20); up(90, 20) }
        assertEquals(listOf(elId("r1")), assertIs<EraseCommand>(r.commands.single()).ids)
        assertEquals(ToolNotice.LockedNotErased(1), r.notices.last())
        assertTrue(r.previews.filterNotNull().all { p -> p.elements.none { it.id == elId("locked") } })
    }

    @Test
    fun eraserOnEmptyCanvas_should_CommitNothing() {
        val ctx = testToolContext(sceneOfElements(r1))
        val r = runScenario(EraserTool(), ctx) { down(500, 500); up(500, 500) }
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun frame_should_AdoptFullyContainedElementsOnly_AndCommitOneStep() {
        val scene = sceneOfElements(r1, r2, r3)
        val ctx = testToolContext(scene)
        val r = runScenario(FrameTool(), ctx) { down(-10, -10); move(60, 60); up(55, 55) }
        val cmd = assertIs<AddFrameCommand>(r.commands.single())
        assertEquals(setOf(elId("r1"), elId("r3")), cmd.memberIds.toSet())
        val history = commitToolCommand(SketchDocumentV2(scene), cmd)
        val frame = history.present.scene.elementsInOrder.filterIsInstance<FrameElement>().single()
        assertEquals(frame.id, history.present.scene[elId("r3")]?.base?.frameId)
        assertNull(history.present.scene[elId("r2")]?.base?.frameId)
        assertEquals(1, history.undoDepth)
        assertEquals(3, history.undo().present.scene.size)
    }

    @Test
    fun frame_should_CommitNothing_When_JustAClick_And_CancelOnEscape() {
        val ctx = testToolContext(sceneOfElements(r1))
        val click = runScenario(FrameTool(), ctx) { down(300, 300); up(300, 300) }
        assertTrue(click.commands.isEmpty())
        val cancelled = runScenario(FrameTool(), ctx) { down(-10, -10); move(60, 60); escape(); up(60, 60) }
        assertTrue(cancelled.commands.isEmpty())
    }

    @Test
    fun hand_should_NeverCommitAnything() {
        val ctx = testToolContext(sceneOfElements(r1))
        val r = runScenario(HandTool(), ctx) { down(10, 10); move(50, 50); up(60, 60) }
        assertTrue(r.commands.isEmpty())
        assertEquals(ToolId.Hand, r.finalTool.id)
    }
}
