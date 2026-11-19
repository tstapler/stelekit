// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.BoundRef
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import dev.stapler.stelekit.sketch.v2.tools.MoveSelectionCommand
import dev.stapler.stelekit.sketch.v2.tools.RectSpec
import dev.stapler.stelekit.sketch.v2.tools.TransformSelectionCommand
import dev.stapler.stelekit.sketch.v2.tools.commitToolCommand
import dev.stapler.stelekit.sketch.v2.tools.elId
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.sceneOfElements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 3.6.3a (UX-40, WCAG 2.5.7, D-39): move element, resize and rotate without a drag; one command, one undo step. */
class NonDragTransformModeTest {
    private val rect = filledRectEl("r1", 0, 0, 100, 60)
    private val scene = sceneOfElements(rect)
    private val sel = listOf(elId("r1"))

    private fun started(action: NonDragAction, step: NudgeStep = NudgeStep.Ten, scene: dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2 = this.scene, selection: List<dev.stapler.stelekit.sketch.v2.scene.ElementId> = sel) =
        assertIs<NonDragStart.Started>(NonDragTransformMode.begin(action, scene, selection, step)).mode

    @Test
    fun resize_should_GrowTheWidthBy30_When_RightIsNudgedThreeTimesAtTenUnits() {
        val mode = started(NonDragAction.Resize).nudge(1, 0).nudge(1, 0).nudge(1, 0)
        assertEquals("width 100 → 130, height 60 → 60", mode.previewText())
        val applied = mode.apply()
        val history = commitToolCommand(SketchDocumentV2(scene), assertIs<TransformSelectionCommand>(applied.command))
        assertEquals(130.0, (history.present.scene[elId("r1")] as RectElement).width.value)
        assertEquals(1, history.undoDepth)
        assertEquals(SketchDocumentV2(scene), history.undo().present)
    }

    @Test
    fun moveElement_should_MoveBy30_KeepBoundArrowsBound_AsOneUndoStep() {
        val container = rect.copy(boundElements = listOf(BoundRef(elId("a1"), BoundKind.Arrow)))
        val arrow = ArrowElement(ElementBase(id = elId("a1")), emptyList(), endBinding = Binding(elId("r1"), 0.5, 0.5))
        val doc = SketchDocumentV2(sceneOfElements(container, arrow))
        val mode = started(NonDragAction.MoveElement, scene = doc.scene).nudge(1, 0).nudge(1, 0).nudge(1, 0)
        assertEquals("x +30, y 0", mode.previewText())
        val history = commitToolCommand(doc, assertIs<MoveSelectionCommand>(mode.apply().command))
        assertEquals(30.0, (history.present.scene[elId("r1")] as RectElement).x.value)
        assertEquals(elId("r1"), (history.present.scene[elId("a1")] as ArrowElement).endBinding?.elementId)
        assertTrue(reconcile(history.present.scene).isClean)
        assertEquals(1, history.undoDepth)
    }

    @Test
    fun rotate_should_TurnByOneDegreePerUnit_So15UnitsMake15Degrees() {
        val mode = started(NonDragAction.Rotate, NudgeStep.One).let { m -> (0 until FIFTEEN).fold(m) { acc, _ -> acc.nudge(1, 0) } }
        assertEquals("angle 0° → 15°", mode.previewText())
        val step = assertIs<TransformSelectionCommand>(mode.apply().command).steps.single()
        val angle = (step as dev.stapler.stelekit.sketch.v2.tools.ElementTransform.Fit).angle!!
        assertEquals(15.0, angle * 180.0 / kotlin.math.PI, 1e-9)
    }

    @Test
    fun arrowsAndStepSetting_should_PickTheNudge_AndShiftIsAlwaysTenUnits() {
        assertEquals("x +1, y 0", started(NonDragAction.MoveElement, NudgeStep.One).nudge(1, 0).previewText())
        assertEquals("x +1, y 0", started(NonDragAction.MoveElement, NudgeStep.Percent).nudge(1, 0).previewText(), "1% of a 100 unit wide element")
        assertEquals("x 0, y +0.6", started(NonDragAction.MoveElement, NudgeStep.Percent).nudge(0, 1).previewText(), "1% of a 60 unit tall element")
        assertEquals("x +10, y 0", started(NonDragAction.MoveElement, NudgeStep.One).nudgeFar(1, 0).previewText())
    }

    @Test
    fun resize_should_ClampAtTheMinimumSide_AndSayMinimumSizeReached() {
        val mode = started(NonDragAction.Resize).nudge(-1, 0).let { m -> (0 until TEN).fold(m) { acc, _ -> acc.nudge(-1, 0) } }
        assertEquals(NonDragNotice.MinimumSize, mode.pendingNotice())
        val applied = mode.apply()
        assertEquals(NonDragNotice.MinimumSize, applied.notice)
        val fit = assertIs<TransformSelectionCommand>(applied.command).steps.single() as dev.stapler.stelekit.sketch.v2.tools.ElementTransform.Fit
        assertTrue(fit.width > 0.0)
    }

    @Test
    fun cancelAndEsc_should_DiscardThePendingNudge_ThenLeaveTheMode() {
        val mode = started(NonDragAction.Resize).nudge(1, 0)
        assertTrue(mode.pending)
        val afterFirstEsc = mode.afterEscape()
        assertEquals(false, afterFirstEsc?.pending)
        assertNull(afterFirstEsc?.apply()?.command, "nothing is committed until Apply")
        assertNull(afterFirstEsc?.afterEscape(), "the second Esc leaves the mode")
    }

    @Test
    fun lockedSelection_should_BeRefused_And_AMixedOneSkipsTheLockedElement() {
        val locked = filledRectEl("l1", 200, 0, 40, 40, RectSpec(locked = true))
        val mixed = sceneOfElements(rect, locked)
        val refused = NonDragTransformMode.begin(NonDragAction.Resize, mixed, listOf(elId("l1")))
        assertEquals(NonDragStart.Refused(NonDragNotice.Locked), refused)
        val mode = started(NonDragAction.MoveElement, scene = mixed, selection = listOf(elId("r1"), elId("l1"))).nudge(1, 0)
        val applied = mode.apply()
        assertEquals(1, applied.skippedLocked)
        assertEquals(listOf(elId("r1")), assertIs<MoveSelectionCommand>(applied.command).ids)
    }

    @Test
    fun nothingSelected_and_NonRotatable_should_BeRefusedWithAReason() {
        assertEquals(NonDragStart.Refused(NonDragNotice.NothingSelected), NonDragTransformMode.begin(NonDragAction.Resize, scene, emptyList()))
        val line = dev.stapler.stelekit.sketch.v2.scene.LineElement(
            ElementBase(elId("ln")),
            listOf(dev.stapler.stelekit.sketch.v2.tools.sp(0, 0), dev.stapler.stelekit.sketch.v2.tools.sp(10, 10)),
        )
        val withLine = sceneOfElements(line)
        assertEquals(NonDragStart.Refused(NonDragNotice.NotRotatable), NonDragTransformMode.begin(NonDragAction.Rotate, withLine, listOf(elId("ln"))))
    }

    @Test
    fun multiSelection_should_ResizeEveryElementAboutTheLeftEdge_InOneCommand() {
        val a = filledRectEl("a", 0, 0, 40, 40)
        val b = filledRectEl("b", 60, 0, 40, 40)
        val two = sceneOfElements(a, b)
        val mode = started(NonDragAction.Resize, scene = two, selection = listOf(elId("a"), elId("b")))
        val applied = (0 until TEN).fold(mode) { m, _ -> m.nudge(1, 0) }.apply()
        val history = commitToolCommand(SketchDocumentV2(two), assertIs<TransformSelectionCommand>(applied.command))
        val ra = history.present.scene[elId("a")] as RectElement
        val rb = history.present.scene[elId("b")] as RectElement
        assertEquals(0.0, ra.x.value)
        assertTrue(rb.x.value > 60.0 && ra.width.value > 40.0)
        assertEquals(1, history.undoDepth)
    }

    private companion object {
        const val FIFTEEN = 15
        const val TEN = 10
    }
}
