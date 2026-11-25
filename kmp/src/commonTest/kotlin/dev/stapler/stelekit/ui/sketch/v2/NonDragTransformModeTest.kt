// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.reconciled
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.BoundRef
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import dev.stapler.stelekit.sketch.v2.tools.ElementTransform
import dev.stapler.stelekit.sketch.v2.tools.MoveSelectionCommand
import dev.stapler.stelekit.sketch.v2.tools.RectSpec
import dev.stapler.stelekit.sketch.v2.tools.TransformSelectionCommand
import dev.stapler.stelekit.sketch.v2.tools.commitToolCommand
import dev.stapler.stelekit.sketch.v2.tools.elId
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.sceneOfElements
import dev.stapler.stelekit.sketch.v2.tools.su
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
        // An opening turns with its wall, so it alone is refused; a line is rotatable now.
        val wall = dev.stapler.stelekit.sketch.v2.scene.WallElement(
            ElementBase(elId("w")), listOf(dev.stapler.stelekit.sketch.v2.tools.sp(0, 0), dev.stapler.stelekit.sketch.v2.tools.sp(400, 0)), dev.stapler.stelekit.sketch.v2.tools.su(72),
        )
        val door = dev.stapler.stelekit.sketch.v2.scene.OpeningElement(ElementBase(elId("d")), elId("w"), 0, dev.stapler.stelekit.sketch.v2.tools.su(100), dev.stapler.stelekit.sketch.v2.tools.su(100))
        val withDoor = sceneOfElements(wall, door).reconciled()
        assertEquals(NonDragStart.Refused(NonDragNotice.NotRotatable), NonDragTransformMode.begin(NonDragAction.Rotate, withDoor, listOf(elId("d"))))
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

    /** "Door opening" at size 20: 120 x 25, like a text box the pointer resize test uses. */
    private fun label(size: Double = 20.0, w: Int = 120, h: Int = 25) = TextElement(
        ElementBase(elId("t1")), su(0), su(0), su(w), su(h), text = "Door opening", originalText = "Door opening", fontSize = size,
    )

    private val label = label()

    /** The single text's step after [nudges] Resize nudges of ([dx], [dy]) at step One (null: Apply commits nothing), and what Apply reported. */
    private fun resizedText(text: TextElement, nudges: Int, dx: Int, dy: Int): Pair<ElementTransform.TextBox?, NonDragApplied> {
        val start = started(NonDragAction.Resize, NudgeStep.One, sceneOfElements(text), listOf(elId("t1")))
        val mode = (0 until nudges).fold(start) { m, _ -> m.nudge(dx, dy) }
        val applied = mode.apply()
        assertEquals(applied.notice, mode.pendingNotice(), "the live notice matches what Apply reports")
        val step = (applied.command as TransformSelectionCommand?)?.steps?.single()
        return step?.let { assertIs<ElementTransform.TextBox>(it) } to applied
    }

    @Test
    fun resize_of_text_should_Shrink_When_LeftOrUpIsNudged_AndGrow_When_RightOrDownIs() {
        val left = assertNotNull(resizedText(label, 12, -1, 0).first, "Left shrinks")
        assertEquals(20.0 * 108.0 / 120.0, left.fontSize, 1e-9)
        val up = assertNotNull(resizedText(label, 5, 0, -1).first, "Up shrinks")
        assertEquals(20.0 * 20.0 / 25.0, up.fontSize, 1e-9)
        assertTrue(assertNotNull(resizedText(label, 12, 1, 0).first).fontSize > 20.0, "Right grows")
        assertTrue(assertNotNull(resizedText(label, 5, 0, 1).first).fontSize > 20.0, "Down grows")
        assertNull(resizedText(label, 5, 0, -1).second.notice, "a shrink inside the limits says nothing")
    }

    @Test
    fun resize_of_text_should_StopAtTheFontLimit_AndSaySo_ButCommitNothingWhenNothingChanges() {
        val (atMin, minApplied) = resizedText(label(size = 8.0), 10, 0, -1)
        assertNull(atMin, "size 8 cannot shrink, so nothing is committed")
        assertEquals(NonDragNotice.MinimumSize, minApplied.notice)
        val (stopped, stoppedApplied) = resizedText(label, 20, 0, -1)
        assertEquals(8.0, assertNotNull(stopped).fontSize, "20 * 5/25 = 4 stops at 8")
        assertEquals(NonDragNotice.MinimumSize, stoppedApplied.notice)
        val (huge, hugeApplied) = resizedText(label(size = 300.0, w = 1800, h = 375), 10, 1, 1)
        assertNull(huge, "an imported 300 cannot grow, so nothing is committed")
        assertEquals(NonDragNotice.MaximumSize, hugeApplied.notice)
        assertTrue(assertNotNull(resizedText(label(size = 300.0, w = 1800, h = 375), 10, -1, -1).first).fontSize < 300.0, "but it can shrink")
    }

    @Test
    fun resize_of_text_should_ScaleTheFont_LikeAPointerCornerDrag_NotStretchTheBox() {
        val one = sceneOfElements(label)
        val mode = started(NonDragAction.Resize, scene = one, selection = listOf(elId("t1"))).nudge(1, 1)
        val step = assertIs<TransformSelectionCommand>(mode.apply().command).steps.single()
        val box = assertIs<ElementTransform.TextBox>(step, "text resizes through TextResize, got $step")
        assertEquals(28.0, box.fontSize, 1e-9, "35/25 grows most, so the font scales by 1.4")
        assertEquals(box.width / box.height, 120.0 / 25.0, 1e-9, "the aspect is kept")
        assertEquals("width 120 → 168, height 25 → 35", mode.previewText())
    }

    @Test
    fun resize_of_aGroupWithText_should_ScaleTheTextFont_LikeAPointerGroupResize() {
        val two = sceneOfElements(label, filledRectEl("r2", 200, 0, 40, 25))
        val mode = (0 until TEN).fold(started(NonDragAction.Resize, scene = two, selection = listOf(elId("t1"), elId("r2")))) { m, _ -> m.nudge(1, 1) }
        val steps = assertIs<TransformSelectionCommand>(mode.apply().command).steps
        val text = assertIs<ElementTransform.TextBox>(steps.single { it.id == elId("t1") })
        assertTrue(text.fontSize > 20.0, "the group grew, so the text font grows: ${text.fontSize}")
    }

    private companion object {
        const val FIFTEEN = 15
        const val TEN = 10
    }
}
